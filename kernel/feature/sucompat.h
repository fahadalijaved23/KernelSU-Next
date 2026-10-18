#ifndef __KSU_H_SUCOMPAT
#define __KSU_H_SUCOMPAT
#include <asm/ptrace.h>
#include <linux/types.h>

extern bool ksu_su_compat_enabled;
extern bool ksu_su_request_enabled;

bool ksu_su_request_should_trace(uid_t uid);

void ksu_sucompat_init(void);
void ksu_sucompat_exit(void);

// Handler functions exported for hook_manager
long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);

/* Samsung fallbacks: used when the syscall-table dispatcher could not be
 * installed (RKP blocked the write). These rewrite pt_regs in place and
 * return true; the caller lets the kernel's own syscall dispatch run the
 * modified syscall once. No syscall is executed from inside these helpers. */
bool ksu_samsung_su_access_redirect(struct pt_regs *regs);
bool ksu_samsung_handle_execve_redirect(struct pt_regs *regs, bool execveat);

#endif
