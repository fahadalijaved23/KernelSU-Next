#ifndef __KSU_H_SAMSUNG_KPROBES
#define __KSU_H_SAMSUNG_KPROBES

#include <linux/init.h>

/*
 * Samsung-specific syscall-entry kprobes.
 *
 * These are a last-resort fallback used only when the normal syscall-table
 * dispatcher install failed (Samsung RKP blocks writes to sys_call_table).
 * In that mode `ksu_dispatcher_nr < 0` and the sys_enter tracepoint bails
 * out for all intercepted syscalls, which would leave execve of
 * /system/bin/su un-redirected -> no root for shell su.
 *
 * The pre-handlers run on the SYSCALL_DEFINE wrappers (process context,
 * schedulable, task_pt_regs reachable through x0) so they can:
 *   - compare the user filename against "/system/bin/su"
 *   - rewrite the filename pointer in the user's pt_regs to point at a
 *     user-stack buffer holding "/data/adb/ksud" (for exec*) or
 *     "/system/bin/sh" (for faccessat/newfstatat)
 *   - escalate the task's cred before the real execve body runs
 *
 * On non-arm64 architectures the init/exit are empty stubs so the module
 * continues to compile. All non-stub code is behind `#ifdef __aarch64__`.
 */
void ksu_samsung_kprobes_init(void);
void ksu_samsung_kprobes_exit(void);

#endif
