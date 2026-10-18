#include "linux/file.h"
#include "linux/fcntl.h"
#include "linux/namei.h"
#include <linux/compiler_types.h>
#include <linux/preempt.h>
#include <linux/printk.h>
#include <linux/mm.h>
#include <linux/pgtable.h>
#include <linux/uaccess.h>
#include <asm/current.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/sched/task_stack.h>
#include <linux/ptrace.h>

#include "arch.h"
#include "policy/allowlist.h"
#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep
#include "runtime/ksud.h"
#include "feature/sucompat.h"
#include "policy/app_profile.h"
#include "hook/syscall_hook.h"
#include "hook/tp_marker.h"
#include "supercall/supercall.h"
#include "sulog/event.h"
#include "ksu.h"
#include "util.h"

#define SU_PATH "/system/bin/su"
#define SH_PATH "/system/bin/sh"

bool ksu_su_compat_enabled __read_mostly = true;

static int su_compat_feature_get(u64 *value)
{
	*value = ksu_su_compat_enabled ? 1 : 0;
	return 0;
}

static int su_compat_feature_set(u64 value)
{
	bool enable = value != 0;
	ksu_su_compat_enabled = enable;
	pr_info("su_compat: set to %d\n", enable);
	return 0;
}

static const struct ksu_feature_handler su_compat_handler = {
	.feature_id = KSU_FEATURE_SU_COMPAT,
	.name = "su_compat",
	.get_handler = su_compat_feature_get,
	.set_handler = su_compat_feature_set,
};

bool ksu_su_request_enabled __read_mostly = true;

static int su_request_feature_get(u64 *value)
{
	*value = ksu_su_request_enabled ? 1 : 0;
	return 0;
}

static int su_request_feature_set(u64 value)
{
	bool enable = value != 0;
	ksu_su_request_enabled = enable;
	pr_info("su_request: set to %d\n", enable);
	ksu_mark_running_process();
	return 0;
}

static const struct ksu_feature_handler su_request_handler = {
	.feature_id = KSU_FEATURE_SU_REQUEST,
	.name = "su_request",
	.get_handler = su_request_feature_get,
	.set_handler = su_request_feature_set,
};

static inline bool su_request_gate(uid_t uid)
{
	return ksu_su_request_enabled && ksu_su_compat_enabled && is_appuid(uid);
}

bool ksu_su_request_should_trace(uid_t uid)
{
	struct app_profile *profile;

	if (!su_request_gate(uid))
		return false;

	rcu_read_lock();
	profile = ksu_get_app_profile(uid);
	rcu_read_unlock();

	if (profile) {
		ksu_put_app_profile(profile);
		return false;
	}
	return true;
}

#define SU_REQUEST_ENV "KSU_SU_REQUEST="

struct su_request_stack {
	u64 envp[2];
	char empty_path[8];
	char env[sizeof(SU_REQUEST_ENV) + 16];
};

static bool su_request_user_args(uid_t uid, char __user **path_user, unsigned long *envp_user)
{
	struct su_request_stack buf;
	struct su_request_stack __user *p;

	memset(&buf, 0, sizeof(buf));
	snprintf(buf.env, sizeof(buf.env), SU_REQUEST_ENV "%u", uid);

	p = (void __user *)((current_user_stack_pointer() - sizeof(buf)) & ~15UL);
	buf.envp[0] = (u64)(unsigned long)p->env;
	buf.envp[1] = 0;
	if (copy_to_user(p, &buf, sizeof(buf)))
		return false;

	*path_user = p->empty_path;
	*envp_user = (unsigned long)p->envp;
	return true;
}

static char __user *ksud_user_path(void)
{
	static const char ksud_path[] = KSUD_PATH;

	return userspace_stack_buffer(ksud_path, sizeof(ksud_path));
}

static char __user *empty_user_path(void)
{
	return userspace_stack_buffer("", sizeof(""));
}

static const char su_path[] = SU_PATH;

static bool is_ksud_exists()
{
	struct path path;

	if (kern_path(KSUD_PATH, 0, &path) < 0) {
		return false;
	}
	path_put(&path);
	return true;
}

long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs)
{
	const char __user **filename_user, *orig_filename;
	long ret;
	const struct cred *old_cred;

	if (!ksu_is_allow_uid_for_current(current_uid().val) &&
	    !su_request_gate(current_uid().val)) {
		goto do_orig_facessat;
	}

	filename_user = (const char __user **)&PT_REGS_PARM2(regs);

	char path[sizeof(su_path) + 1];
	memset(path, 0, sizeof(path));
	strncpy_from_user_nofault(path, *filename_user, sizeof(path));

	if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
		old_cred = override_creds(ksu_cred);
		if (is_ksud_exists()) {
			ksu_compat_sulog('a');
			pr_info("faccessat su->ksud!\n");
			orig_filename = *filename_user;
			*filename_user = ksud_user_path();
			ret = ksu_syscall_table[orig_nr](regs);
			revert_creds(old_cred);
			*filename_user = orig_filename;
			return ret;
		} else {
			revert_creds(old_cred);
		}
	}

do_orig_facessat:
	return ksu_syscall_table[orig_nr](regs);
}

long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs)
{
	const char __user **filename_user, *orig_filename;
	long ret;
	const struct cred *old_cred;

	if (!ksu_is_allow_uid_for_current(current_uid().val) &&
	    !su_request_gate(current_uid().val)) {
		goto do_orig_stat;
	}

	filename_user = (const char __user **)&PT_REGS_PARM2(regs);

	char path[sizeof(su_path) + 1];
	memset(path, 0, sizeof(path));
	strncpy_from_user_nofault(path, *filename_user, sizeof(path));

	if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
		old_cred = override_creds(ksu_cred);
		if (is_ksud_exists()) {
			ksu_compat_sulog('s');
			pr_info("newfstatat su->ksud!\n");
			orig_filename = *filename_user;
			*filename_user = ksud_user_path();
			ret = ksu_syscall_table[orig_nr](regs);
			revert_creds(old_cred);
			*filename_user = orig_filename;
			return ret;
		} else {
			revert_creds(old_cred);
		}
	}

do_orig_stat:
	return ksu_syscall_table[orig_nr](regs);
}

static long ksu_handle_execve_sucompat_common(const char __user **filename_user,
					      const char __user *const __user *argv_user, unsigned long envp,
					      bool execveat, int orig_nr, struct pt_regs *regs)
{
	const char __user *fn;
	struct ksu_sulog_pending_event *pending_sucompat = NULL;
	char path[sizeof(su_path) + 1];
	long ret, orig_regs[5];
	unsigned long addr;
	int su_fd = -1;
	int tmp_fd;
	struct file *ksud_file;
	const struct cred *old_cred;
	bool su_request = false;
	char __user *exec_path_user;
	unsigned long exec_envp = envp;

	if (execveat && ((int)PT_REGS_SYSCALL_PARM1(regs) != AT_FDCWD || (int)PT_REGS_PARM5(regs) != 0))
		goto do_orig_execve;

	if (unlikely(!filename_user))
		goto do_orig_execve;

	if (!ksu_is_allow_uid_for_current(current_uid().val)) {
		if (!su_request_gate(current_uid().val))
			goto do_orig_execve;
		su_request = true;
	}

	addr = untagged_addr((unsigned long)*filename_user);
	fn = (const char __user *)addr;
	memset(path, 0, sizeof(path));
	ret = strncpy_from_user(path, fn, sizeof(path));

	if (ret < 0) {
		pr_warn("Access filename when execve failed: %ld", ret);
		goto do_orig_execve;
	}

	if (likely(memcmp(path, su_path, sizeof(su_path))))
		goto do_orig_execve;

	ksu_compat_sulog('x');
	pr_info("sys_execve su found%s\n", su_request ? " (request)" : "");

	tmp_fd = get_unused_fd_flags(O_CLOEXEC);
	if (tmp_fd < 0) {
		pr_err("alloc tmp fd err: %d\n", tmp_fd);
		goto do_orig_execve;
	}

	old_cred = override_creds(ksu_cred);
	ksud_file = filp_open(KSUD_PATH, O_PATH, 0);
	revert_creds(old_cred);
	if (IS_ERR(ksud_file)) {
		pr_err("open ksud err: %ld\n", PTR_ERR(ksud_file));
		put_unused_fd(tmp_fd);
		goto do_orig_execve;
	}

	fd_install(tmp_fd, ksud_file);

	if (su_request) {
		if (!su_request_user_args(current_uid().val, &exec_path_user, &exec_envp)) {
			ksu_close_fd(tmp_fd);
			goto do_orig_execve;
		}
	} else {
		exec_path_user = empty_user_path();
	}

	pending_sucompat = ksu_sulog_capture_sucompat(*filename_user, argv_user, GFP_KERNEL);
	// execve(file, argv, environ)
	// execveat(fd, file, argv, environ, flags)
	orig_regs[0] = PT_REGS_SYSCALL_PARM1(regs);
	orig_regs[1] = regs->__PT_PARM2_REG;
	orig_regs[2] = regs->__PT_PARM3_REG;
	orig_regs[3] = regs->__PT_SYSCALL_PARM4_REG;
	orig_regs[4] = regs->__PT_PARM5_REG;
	regs->__PT_PARM5_REG = AT_EMPTY_PATH;
	regs->__PT_SYSCALL_PARM4_REG = exec_envp;
	regs->__PT_PARM3_REG = (unsigned long)argv_user;
	regs->__PT_PARM2_REG = (unsigned long)exec_path_user;
	PT_REGS_SYSCALL_PARM1(regs) = tmp_fd;

	ret = escape_with_root_profile();
	if (ret) {
		pr_err("escape_with_root_profile failed: %ld\n", ret);
	}
	ksu_sulog_emit_pending(pending_sucompat, ret, GFP_KERNEL);

	ret = ksu_syscall_table[__NR_execveat](regs);
	if (ret < 0) {
		ksu_close_fd(tmp_fd);
		PT_REGS_SYSCALL_PARM1(regs) = orig_regs[0];
		regs->__PT_PARM2_REG = orig_regs[1];
		regs->__PT_PARM3_REG = orig_regs[2];
		regs->__PT_SYSCALL_PARM4_REG = orig_regs[3];
		regs->__PT_PARM5_REG = orig_regs[4];
	} else if (!su_request) {
		// Only grant the scoped driver session fd to a genuinely allowed app.
		// A pending request gets no session fd; ksud re-execs su once the user
		// allows it, which then takes this normal path.
		su_fd = ksu_install_su_fd();
		if (su_fd < 0) {
			pr_warn("install su session fd failed: %d\n", su_fd);
		}
	}
	return ret;

do_orig_execve:
	return ksu_syscall_table[orig_nr](regs);
}

long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs)
{
	return ksu_handle_execve_sucompat_common(filename_user, (const char __user *const __user *)PT_REGS_PARM2(regs),
						 PT_REGS_PARM3(regs), false, orig_nr, regs);
}

long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs)
{
	return ksu_handle_execve_sucompat_common(filename_user, (const char __user *const __user *)PT_REGS_PARM3(regs),
						 PT_REGS_SYSCALL_PARM4(regs), true, orig_nr, regs);
}

static char __user *sh_user_path(void)
{
	static const char sh_path[] = SH_PATH;
	return userspace_stack_buffer(sh_path, sizeof(sh_path));
}

/* Samsung fallback: dispatcher is off, so we rewrite the user filename pointer
 * from "/system/bin/su" to "/system/bin/sh" and let the kernel's faccessat/
 * newfstatat run with the modified pointer. sh is always world-accessible so
 * the syscall returns 0 without any cred override. The user program sees the
 * stat/access succeed and goes on to execve "/system/bin/su", which hits the
 * execve redirect below. The user's own string is untouched - we only point
 * filename_user at a different user address for one syscall. */
bool ksu_samsung_su_access_redirect(struct pt_regs *regs)
{
	const char __user **filename_user;
	char path[sizeof(su_path) + 1];

	if (!ksu_su_compat_enabled)
		return false;

	if (!ksu_is_allow_uid_for_current(current_uid().val) &&
	    !su_request_gate(current_uid().val))
		return false;

	filename_user = (const char __user **)&PT_REGS_PARM2(regs);
	if (!filename_user || !*filename_user)
		return false;

	memset(path, 0, sizeof(path));
	strncpy_from_user_nofault(path, *filename_user, sizeof(path));

	if (memcmp(path, su_path, sizeof(su_path)))
		return false;

	if (!is_ksud_exists())
		return false;

	*filename_user = sh_user_path();
	return true;
}

/* Samsung fallback: rewrite an execve/execveat of "/system/bin/su" into an
 * execveat(ksud_fd, "", argv, envp, AT_EMPTY_PATH) with the current task's
 * cred upgraded to a root profile. The caller (sys_enter tracepoint) must
 * also set regs->syscallno = __NR_execveat when we return true, so the
 * kernel's own dispatch runs the ONE modified execveat - no double exec. */
bool ksu_samsung_handle_execve_redirect(struct pt_regs *regs, bool execveat)
{
	const char __user **filename_user;
	const char __user *const __user *argv_user;
	unsigned long envp;
	const char __user *fn;
	char path[sizeof(su_path) + 1];
	long ret;
	unsigned long addr;
	int tmp_fd;
	struct file *ksud_file;
	const struct cred *old_cred;
	bool su_request = false;
	char __user *exec_path_user;
	unsigned long exec_envp;

	if (!ksu_su_compat_enabled)
		return false;

	if (execveat && ((int)PT_REGS_SYSCALL_PARM1(regs) != AT_FDCWD ||
			 (int)PT_REGS_PARM5(regs) != 0))
		return false;

	filename_user = execveat ?
		(const char __user **)&PT_REGS_PARM2(regs) :
		(const char __user **)&PT_REGS_SYSCALL_PARM1(regs);
	if (!filename_user)
		return false;

	if (!ksu_is_allow_uid_for_current(current_uid().val)) {
		if (!su_request_gate(current_uid().val))
			return false;
		su_request = true;
	}

	addr = untagged_addr((unsigned long)*filename_user);
	fn = (const char __user *)addr;
	memset(path, 0, sizeof(path));
	ret = strncpy_from_user(path, fn, sizeof(path));
	if (ret < 0)
		return false;

	if (likely(memcmp(path, su_path, sizeof(su_path))))
		return false;

	pr_info("samsung: sys_execve su found%s\n", su_request ? " (request)" : "");

	argv_user = execveat ?
		(const char __user *const __user *)PT_REGS_PARM3(regs) :
		(const char __user *const __user *)PT_REGS_PARM2(regs);
	envp = execveat ? PT_REGS_PARM4(regs) : PT_REGS_PARM3(regs);
	exec_envp = envp;

	tmp_fd = get_unused_fd_flags(O_CLOEXEC);
	if (tmp_fd < 0) {
		pr_err("samsung: alloc tmp fd err: %d\n", tmp_fd);
		return false;
	}

	old_cred = override_creds(ksu_cred);
	ksud_file = filp_open(KSUD_PATH, O_PATH, 0);
	revert_creds(old_cred);
	if (IS_ERR(ksud_file)) {
		pr_err("samsung: open ksud err: %ld\n", PTR_ERR(ksud_file));
		put_unused_fd(tmp_fd);
		return false;
	}
	fd_install(tmp_fd, ksud_file);

	if (su_request) {
		if (!su_request_user_args(current_uid().val, &exec_path_user, &exec_envp)) {
			ksu_close_fd(tmp_fd);
			return false;
		}
	} else {
		exec_path_user = empty_user_path();
	}

	regs->__PT_PARM5_REG = AT_EMPTY_PATH;
	regs->__PT_SYSCALL_PARM4_REG = exec_envp;
	regs->__PT_PARM3_REG = (unsigned long)argv_user;
	regs->__PT_PARM2_REG = (unsigned long)exec_path_user;
	PT_REGS_SYSCALL_PARM1(regs) = tmp_fd;

	ret = escape_with_root_profile();
	if (ret)
		pr_err("samsung: escape_with_root_profile failed: %ld\n", ret);

	if (!su_request) {
		int su_fd = ksu_install_su_fd();
		if (su_fd < 0)
			pr_warn("samsung: install su session fd failed: %d\n", su_fd);
	}

	return true;
}

// sucompat: permitted process can execute 'su' to gain root access.
void __init ksu_sucompat_init()
{
	if (ksu_register_feature_handler(&su_compat_handler)) {
		pr_err("Failed to register su_compat feature handler\n");
	}
	if (ksu_register_feature_handler(&su_request_handler)) {
		pr_err("Failed to register su_request feature handler\n");
	}
}

void __exit ksu_sucompat_exit()
{
	ksu_unregister_feature_handler(KSU_FEATURE_SU_COMPAT);
	ksu_unregister_feature_handler(KSU_FEATURE_SU_REQUEST);
}
