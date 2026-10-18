//! Magisk-style su-request broker.
//!
//! When an app that has no app profile yet calls `su`, the kernel sucompat hook
//! routes the exec to ksud (as root, with a sanitized environment and the env
//! var `KSU_SU_REQUEST=<uid>` set) instead of handing out a shell. That ksud
//! process is the *request client*: it connects to a persistent root daemon,
//! which launches the manager's popup and relays the user's Allow/Deny decision
//! back. On Allow the client re-execs `/system/bin/su`; by then the manager has
//! granted the app an app profile, so the kernel takes the normal path and the
//! shell starts with root. On Deny (or timeout) the client exits without ever
//! running the caller's shell.
//!
//! Three roles share one Unix socket under the working dir:
//!   * `ksud sudaemon`            - the listener (started at boot)
//!   * request client             - `root_shell()` when `KSU_SU_REQUEST` is set
//!   * `ksud su-reply --id ..`    - run by the manager to deliver the decision
//!
//! All three run as root in the KernelSU domain, so there is no cross-domain
//! SELinux socket problem to solve.

use crate::{defs, utils};
use anyhow::{Context, Result, bail};
use log::{error, info, warn};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Sender, channel};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// Filesystem socket, root-only directory.
fn socket_path() -> String {
    format!("{}su_daemon.sock", defs::WORKING_DIR)
}

/// How long the daemon waits for the user before defaulting to deny.
const DECISION_TIMEOUT: Duration = Duration::from_secs(60);

/// Candidate manager package names tried in order. The first entry is this
/// build's actual manager; the rest let a shared ksud binary still find a
/// popup activity when the manager was renamed or forked.
const CANDIDATE_MANAGER_PACKAGES: &[&str] = &[
    "com.rifsxd.ksunext",
    "me.weishu.kernelsu",
    "com.sukisu.ultra",
];

type Pending = Arc<Mutex<HashMap<u64, Sender<bool>>>>;

fn next_reqid() -> u64 {
    static COUNTER: AtomicU64 = AtomicU64::new(1);
    COUNTER.fetch_add(1, Ordering::Relaxed)
}

fn package_installed(pkg: &str) -> bool {
    // `pm path <pkg>` prints the APK path on success and exits 0; fails on
    // uninstalled packages. Cheap and sufficient to pick the right manager.
    Command::new("pm")
        .args(["path", pkg])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .output()
        .map(|o| o.status.success() && !o.stdout.is_empty())
        .unwrap_or(false)
}

fn resolve_manager_package() -> String {
    for pkg in CANDIDATE_MANAGER_PACKAGES {
        if package_installed(pkg) {
            info!("sudaemon: using manager package {pkg}");
            return (*pkg).to_string();
        }
    }
    let fallback = CANDIDATE_MANAGER_PACKAGES[0].to_string();
    warn!("sudaemon: no known manager package installed; falling back to {fallback}");
    fallback
}

fn manager_package() -> &'static str {
    static PKG: OnceLock<String> = OnceLock::new();
    PKG.get_or_init(resolve_manager_package)
}

// ---------------------------------------------------------------------------
// Daemon
// ---------------------------------------------------------------------------

/// Spawn `ksud sudaemon` detached, if it is not already running.
pub fn spawn_sudaemon() -> Result<()> {
    if utils::create_daemon(true)? {
        let mut command = Command::new("/proc/self/exe");
        command
            .arg("sudaemon")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .current_dir("/");
        let err = command.exec();
        error!("failed to exec sudaemon: {err:#}");
        unsafe {
            libc::_exit(1);
        }
    }
    Ok(())
}

pub fn ensure_sudaemon_running() -> Result<()> {
    // Avoid double-starting: if someone is already listening on the socket,
    // leave them alone. Prevents on_post_fs_data + late_load from spawning
    // two daemons that race on bind().
    if UnixStream::connect(socket_path()).is_ok() {
        info!("sudaemon already running on {}", socket_path());
        return Ok(());
    }
    spawn_sudaemon()
}

pub fn run_sudaemon() -> Result<()> {
    let path = socket_path();
    // A stale socket file from a previous boot would make bind() fail with
    // EADDRINUSE even though nobody is listening.
    let _ = std::fs::remove_file(&path);

    let listener =
        UnixListener::bind(&path).with_context(|| format!("bind su daemon socket at {path}"))?;
    // Root-only; the clients and the manager's reply command all run as root.
    let _ = std::fs::set_permissions(&path, std::os::unix::fs::PermissionsExt::from_mode(0o600));

    info!("sudaemon listening on {path}");

    let pending: Pending = Arc::new(Mutex::new(HashMap::new()));

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let pending = Arc::clone(&pending);
                std::thread::spawn(move || {
                    if let Err(e) = handle_conn(stream, &pending) {
                        warn!("sudaemon connection error: {e:#}");
                    }
                });
            }
            Err(e) => {
                warn!("sudaemon accept failed: {e:#}");
            }
        }
    }

    Ok(())
}

fn handle_conn(stream: UnixStream, pending: &Pending) -> Result<()> {
    let mut reader = BufReader::new(stream.try_clone().context("clone su daemon stream")?);
    let mut line = String::new();
    reader.read_line(&mut line).context("read request line")?;
    let line = line.trim_end();
    let fields: Vec<&str> = line.split('\t').collect();

    match fields.first().copied() {
        Some("REQ") => handle_request(stream, pending, &fields),
        Some("REPLY") => handle_reply(pending, &fields),
        other => bail!("unknown su daemon command: {other:?}"),
    }
}

/// `REQ\t<uid>\t<pid>` — a request client is blocking for a decision.
fn handle_request(mut stream: UnixStream, pending: &Pending, fields: &[&str]) -> Result<()> {
    let uid: u32 = fields
        .get(1)
        .and_then(|s| s.parse().ok())
        .context("missing/invalid uid in REQ")?;
    let pid: i32 = fields.get(2).and_then(|s| s.parse().ok()).unwrap_or(-1);

    let reqid = next_reqid();
    let (tx, rx) = channel::<bool>();
    pending.lock().unwrap().insert(reqid, tx);

    info!("su request #{reqid}: uid={uid} pid={pid}");

    if let Err(e) = launch_prompt(reqid, uid, pid) {
        // Could not show UI -> fail closed (deny) rather than hang the caller.
        error!("failed to launch su prompt for #{reqid}: {e:#}");
        pending.lock().unwrap().remove(&reqid);
        stream.write_all(b"deny\n").ok();
        return Ok(());
    }

    let allow = match rx.recv_timeout(DECISION_TIMEOUT) {
        Ok(decision) => decision,
        Err(_) => {
            info!("su request #{reqid} timed out, denying");
            false
        }
    };
    pending.lock().unwrap().remove(&reqid);

    let reply = if allow { "allow\n" } else { "deny\n" };
    stream.write_all(reply.as_bytes()).context("write decision")?;
    info!("su request #{reqid}: {}", reply.trim_end());
    Ok(())
}

/// `REPLY\t<reqid>\t<allow|deny>` — the manager delivered a decision.
fn handle_reply(pending: &Pending, fields: &[&str]) -> Result<()> {
    let reqid: u64 = fields
        .get(1)
        .and_then(|s| s.parse().ok())
        .context("missing/invalid reqid in REPLY")?;
    let allow = matches!(fields.get(2).copied(), Some("allow"));

    if let Some(tx) = pending.lock().unwrap().remove(&reqid) {
        // The receiver may already be gone (timed out); ignore send errors.
        let _ = tx.send(allow);
    } else {
        warn!("su reply for unknown/expired request #{reqid}");
    }
    Ok(())
}

/// Launch the manager's popup activity for this request.
fn launch_prompt(reqid: u64, uid: u32, pid: i32) -> Result<()> {
    // Use the relative-class form `pkg/.ui.SuRequestActivity`; `am start -n`
    // expands the leading `.` to <pkg>.ui.SuRequestActivity, so this works
    // whichever candidate manager package `manager_package()` resolves to.
    let component = format!("{}/.ui.SuRequestActivity", manager_package());
    info!("sudaemon: launching su prompt #{reqid} via {component} uid={uid} pid={pid}");
    let status = Command::new("am")
        .args([
            "start",
            "--user",
            "current",
            "-n",
            &component,
            "-a",
            "android.intent.action.MAIN",
            "-f",
            // NEW_TASK | EXCLUDE_FROM_RECENTS
            "0x10800000",
            "--el",
            "ksu_reqid",
            &reqid.to_string(),
            "--ei",
            "ksu_uid",
            &uid.to_string(),
            "--ei",
            "ksu_pid",
            &pid.to_string(),
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("spawn am start")?;

    if !status.success() {
        bail!("am start exited with {status}");
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Request client (called from su::root_shell when KSU_SU_REQUEST is set)
// ---------------------------------------------------------------------------

fn connect_to_daemon() -> Result<UnixStream> {
    let path = socket_path();
    // Fast path: daemon already running.
    if let Ok(s) = UnixStream::connect(&path) {
        return Ok(s);
    }
    // Daemon not yet running; start it and wait up to 2 s.
    spawn_sudaemon()?;
    for _ in 0..20 {
        std::thread::sleep(Duration::from_millis(100));
        if let Ok(s) = UnixStream::connect(&path) {
            return Ok(s);
        }
    }
    UnixStream::connect(&path).context("connect to sudaemon after spawn")
}

/// Ask the daemon for a decision. Returns true if the user allowed root.
pub fn request_decision(uid: u32) -> Result<bool> {
    let pid = std::process::id();
    let mut stream = connect_to_daemon().context("connect to sudaemon")?;
    stream
        .set_read_timeout(Some(DECISION_TIMEOUT + Duration::from_secs(5)))
        .ok();
    writeln!(stream, "REQ\t{uid}\t{pid}").context("send su request")?;
    stream.flush().ok();

    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line).context("read su decision")?;
    Ok(line.trim_end() == "allow")
}

// ---------------------------------------------------------------------------
// Reply command (run by the manager as root)
// ---------------------------------------------------------------------------

pub fn send_reply(reqid: u64, allow: bool) -> Result<()> {
    let mut stream = UnixStream::connect(socket_path()).context("connect to sudaemon")?;
    let decision = if allow { "allow" } else { "deny" };
    writeln!(stream, "REPLY\t{reqid}\t{decision}").context("send su reply")?;
    stream.flush().ok();
    Ok(())
}
