// SPDX-License-Identifier: GPL-2.0-only
/*
 * Samsung-specific fallback: kprobes on the arm64 SYSCALL_DEFINE wrappers for
 * execve / execveat / faccessat / newfstatat that re-implement the sucompat
 * "/system/bin/su" redirect at syscall entry.
 *
 * On Samsung Galaxy Fold's 6.12 kernel the Samsung Real-time Kernel Protection
 * (RKP) refuses any write to the sys_call_table, so the normal
 * "dispatcher at a sys_ni_syscall slot" trick that KSU-Next uses to route
 * hooked syscalls through a single unified entry cannot be installed.
 * ksu_syscall_hook_init() detects that failure and leaves
 * ksu_dispatcher_nr == -1, which forces the sys_enter tracepoint handler to
 * skip syscalls it has no safe way to intercept.
 *
 * Without any fallback the chain breaks: an app's `exec("/system/bin/su")`
 * never reaches ksu_handle_execve_sucompat(), so su is not redirected to
 * /data/adb/ksud and the shell gets ENOENT instead of root. We cannot do the
 * work from the sys_enter tracepoint either -- that runs in atomic context
 * (preempt disabled, in_atomic() true) and ksu_handle_execve_sucompat ->
 * filp_open() / alloc_uid() / wait_for_completion() in ksu_commit_creds on
 * KDP kernels will trigger "BUG: scheduling while atomic".
 *
 * A kprobe pre_handler on `__arm64_sys_execve` runs on the real syscall
 * stack in process context after kernel entry has set up a schedulable
 * task context, so it is safe to call sleeping helpers from here. We also
 * get direct access to the original SYSCALL_DEFINE pt_regs via x0 (the
 * wrapper's single `const struct pt_regs *` argument), which is exactly
 * where the filename user-pointer lives.
 */

#ifdef __aarch64__

#include <linux/compiler.h>
#include <linux/cred.h>
#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/fs.h>
#include <linux/kprobes.h>
#include <linux/namei.h>
#include <linux/printk.h>
#include <linux/ptrace.h>
#include <linux/sched.h>
#include <linux/string.h>
#include <linux/types.h>
#include <linux/uaccess.h>
#include <linux/uidgid.h>

#include "arch.h"
#include "feature/sucompat.h"
#include "feature/sucompat_internal.h"
#include "hook/arm64/samsung_kprobes.h"
#include "ksu.h"
#include "util.h"
#include "klog.h" // IWYU pragma: keep
#include "policy/allowlist.h"
#include "policy/app_profile.h"
#include "runtime/ksud.h"

/*
 * Fallback target for faccessat / newfstatat when userspace probes
 * "/system/bin/su". We only need a world-accessible path that always exists
 * on Android; `/system/bin/sh` fits and avoids leaking the /data path.
 */
static const char sh_path[] = "/system/bin/sh";

static char __user *sh_user_path(void)
{
	return userspace_stack_buffer(sh_path, sizeof(sh_path));
}

/* Local copies of helpers that remain static inside sucompat.c. */
static bool samsung_ksud_exists(void)
{
	struct path p;
	if (kern_path(KSUD_PATH, 0, &p) < 0)
		return false;
	path_put(&p);
	return true;
}

static bool samsung_su_request_gate(uid_t uid)
{
	return ksu_su_request_enabled && ksu_su_compat_enabled && is_appuid(uid);
}

static bool samsung_uid_can_use_su(uid_t uid)
{
	return ksu_is_allow_uid_for_current(uid) || samsung_su_request_gate(uid);
}

/*
 * Core path-match: copy the current syscall's filename argument out of the
 * user and return true iff it is exactly "/system/bin/su". The caller passes
 * the slot in syscall_regs so this function cannot inadvertently touch the
 * wrong register.
 */
static bool samsung_filename_is_su(const char __user *user_ptr)
{
	char path[sizeof(su_path) + 1];
	unsigned long addr;

	if (!user_ptr)
		return false;

	addr = untagged_addr((unsigned long)user_ptr);
	memset(path, 0, sizeof(path));

	/*
	 * Use the sleeping copy variant -- we are in process context (kprobe
	 * BRK trap taken on the syscall entry path) and the user page may need
	 * to be faulted in. The nofault variant used in the dispatcher-driven
	 * sucompat.c handlers only works because that path has already touched
	 * the page via arg copy; the kprobe fires earlier, before any kernel
	 * code has read filename, so we must tolerate faulting here.
	 */
	if (strncpy_from_user(path, (const char __user *)addr, sizeof(path)) < 0)
		return false;

	return memcmp(path, su_path, sizeof(su_path)) == 0;
}

/* ------------------------------------------------------------------ */
/* execve / execveat                                                   */
/* ------------------------------------------------------------------ */

static int samsung_execve_common(struct pt_regs *syscall_regs, bool execveat)
{
	const char __user **filename_slot;
	const char __user *orig_filename;
	char __user *rewrite;
	uid_t uid;
	long ret;

	if (!ksu_su_compat_enabled)
		return 0;

	if (execveat) {
		/*
		 * Only intercept execveat forms that are equivalent to execve:
		 * execveat(AT_FDCWD, path, argv, envp, 0). Anything else (an
		 * fd-based exec, or AT_EMPTY_PATH / AT_SYMLINK_NOFOLLOW) is not
		 * our concern and we must leave it fully untouched.
		 */
		if ((int)PT_REGS_SYSCALL_PARM1(syscall_regs) != AT_FDCWD ||
		    (int)PT_REGS_PARM5(syscall_regs) != 0)
			return 0;
		filename_slot = (const char __user **)&PT_REGS_PARM2(syscall_regs);
	} else {
		/*
		 * execve(filename, argv, envp): filename is the syscall's first
		 * argument. On arm64 that is x0, which SYSCALL_DEFINE preserves
		 * in regs[0] of the pt_regs it receives.
		 */
		filename_slot = (const char __user **)&PT_REGS_SYSCALL_PARM1(syscall_regs);
	}

	uid = current_uid().val;
	if (!samsung_uid_can_use_su(uid))
		return 0;

	orig_filename = *filename_slot;
	if (!samsung_filename_is_su(orig_filename))
		return 0;

	/*
	 * Check /data/adb/ksud exists under elevated cred before we commit to
	 * the swap, so a mis-provisioned device just gets normal ENOENT on
	 * /system/bin/su instead of a weirder failure. override_creds is
	 * stack-local and does not sleep, safe regardless of KDP policy.
	 */
	{
		const struct cred *old_cred = override_creds(ksu_cred);
		bool exists = samsung_ksud_exists();
		revert_creds(old_cred);
		if (!exists)
			return 0;
	}

	pr_info("samsung_kprobe: %s su->ksud for pid=%d uid=%u\n",
		execveat ? "execveat" : "execve", current->pid, uid);

	/*
	 * Escalate the task's cred BEFORE we hand control back to the real
	 * __arm64_sys_execve body. escape_with_root_profile() takes the KDP
	 * path on Samsung (workqueue + wait_for_completion). This is the one
	 * step that we specifically moved out of the sys_enter tracepoint:
	 * from a kprobe pre_handler the task is in process context on a real
	 * kernel stack, so the KDP commit worker can run and we can wait on
	 * its completion without triggering "scheduling while atomic".
	 *
	 * We deliberately do NOT fail-close here: if escalation returns a
	 * non-zero error we still swap the filename pointer so the user gets
	 * ksud (which will at least print a diagnostic) rather than ENOENT on
	 * /system/bin/su, which is confusing in the field.
	 */
	ret = escape_with_root_profile();
	if (ret)
		pr_err("samsung_kprobe: escape_with_root_profile failed: %ld\n", ret);

	rewrite = ksud_user_path();
	if (!rewrite) {
		/*
		 * Writing the ksud path into the user stack failed (e.g. the
		 * user stack pointer is bogus). Nothing more we can do; let the
		 * original syscall proceed with its original filename.
		 */
		pr_err("samsung_kprobe: ksud_user_path copy_to_user failed\n");
		return 0;
	}

	*filename_slot = rewrite;
	return 0;
}

static int samsung_execve_handler(struct kprobe *p, struct pt_regs *regs)
{
	return samsung_execve_common(PT_REAL_REGS(regs), false);
}

static int samsung_execveat_handler(struct kprobe *p, struct pt_regs *regs)
{
	return samsung_execve_common(PT_REAL_REGS(regs), true);
}

/* ------------------------------------------------------------------ */
/* faccessat / newfstatat                                              */
/* ------------------------------------------------------------------ */

static int samsung_pathcheck_common(struct pt_regs *syscall_regs,
				    const char *tag)
{
	const char __user **filename_slot;
	char __user *rewrite;
	uid_t uid;

	if (!ksu_su_compat_enabled)
		return 0;

	uid = current_uid().val;
	if (!samsung_uid_can_use_su(uid))
		return 0;

	/*
	 * Both faccessat(dirfd, filename, mode[, flags]) and
	 * newfstatat(dirfd, filename, statbuf, flags) carry the filename in
	 * x1 after the SYSCALL_DEFINE wrapper, i.e. PT_REGS_PARM2 of the
	 * syscall pt_regs.
	 */
	filename_slot = (const char __user **)&PT_REGS_PARM2(syscall_regs);

	if (!samsung_filename_is_su(*filename_slot))
		return 0;

	pr_info("samsung_kprobe: %s su->sh for pid=%d uid=%u\n",
		tag, current->pid, uid);

	rewrite = sh_user_path();
	if (!rewrite)
		return 0;

	*filename_slot = rewrite;
	return 0;
}

static int samsung_faccessat_handler(struct kprobe *p, struct pt_regs *regs)
{
	return samsung_pathcheck_common(PT_REAL_REGS(regs), "faccessat");
}

static int samsung_newfstatat_handler(struct kprobe *p, struct pt_regs *regs)
{
	return samsung_pathcheck_common(PT_REAL_REGS(regs), "newfstatat");
}

/* ------------------------------------------------------------------ */
/* Registration                                                        */
/* ------------------------------------------------------------------ */

static struct kprobe samsung_execve_kp = {
	.symbol_name = "__arm64_sys_execve",
	.pre_handler = samsung_execve_handler,
};

static struct kprobe samsung_execveat_kp = {
	.symbol_name = "__arm64_sys_execveat",
	.pre_handler = samsung_execveat_handler,
};

static struct kprobe samsung_faccessat_kp = {
	.symbol_name = "__arm64_sys_faccessat",
	.pre_handler = samsung_faccessat_handler,
};

static struct kprobe samsung_newfstatat_kp = {
	.symbol_name = "__arm64_sys_newfstatat",
	.pre_handler = samsung_newfstatat_handler,
};

/*
 * Track which kprobes successfully registered so the exit path can call
 * unregister_kprobe only on live probes. A kprobe that failed to register
 * must not be fed back to unregister_kprobe.
 */
static bool samsung_execve_live;
static bool samsung_execveat_live;
static bool samsung_faccessat_live;
static bool samsung_newfstatat_live;

static void samsung_register_one(struct kprobe *kp, bool *flag, const char *tag)
{
	int rc = register_kprobe(kp);
	if (rc) {
		pr_err("samsung_kprobes: register %s failed: %d\n", tag, rc);
		*flag = false;
		return;
	}
	*flag = true;
	pr_info("samsung_kprobes: %s installed at %pS\n", tag, kp->addr);
}

static void samsung_unregister_one(struct kprobe *kp, bool *flag, const char *tag)
{
	if (!*flag)
		return;
	unregister_kprobe(kp);
	*flag = false;
	pr_info("samsung_kprobes: %s removed\n", tag);
}

void ksu_samsung_kprobes_init(void)
{
	pr_info("samsung_kprobes: installing fallback kprobes (dispatcher unavailable)\n");

	samsung_register_one(&samsung_execve_kp, &samsung_execve_live, "execve");
	samsung_register_one(&samsung_execveat_kp, &samsung_execveat_live, "execveat");
	samsung_register_one(&samsung_faccessat_kp, &samsung_faccessat_live, "faccessat");
	samsung_register_one(&samsung_newfstatat_kp, &samsung_newfstatat_live, "newfstatat");
}

void ksu_samsung_kprobes_exit(void)
{
	samsung_unregister_one(&samsung_execve_kp, &samsung_execve_live, "execve");
	samsung_unregister_one(&samsung_execveat_kp, &samsung_execveat_live, "execveat");
	samsung_unregister_one(&samsung_faccessat_kp, &samsung_faccessat_live, "faccessat");
	samsung_unregister_one(&samsung_newfstatat_kp, &samsung_newfstatat_live, "newfstatat");
}

#else /* !__aarch64__ */

/*
 * Non-arm64 architectures do not need the Samsung RKP fallback -- the
 * dispatcher install never fails there. These stubs let the hook_manager
 * call ksu_samsung_kprobes_init()/exit() unconditionally without a per-arch
 * ifdef at the call site.
 */

void ksu_samsung_kprobes_init(void) {}
void ksu_samsung_kprobes_exit(void) {}

#endif /* __aarch64__ */
