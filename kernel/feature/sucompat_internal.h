#ifndef __KSU_H_SUCOMPAT_INTERNAL
#define __KSU_H_SUCOMPAT_INTERNAL

#include <linux/types.h>

/*
 * Shared sucompat helpers used by sucompat.c and the Samsung kprobe fallback
 * (kernel/hook/arm64/samsung_kprobes.c).
 *
 * These produce user-visible strings into current's user stack and are only
 * safe to call from a syscall path running in process context on behalf of
 * the task whose exec/stat is being redirected. They are intentionally kept
 * out of the public sucompat.h so they do not become a general-purpose API.
 */

/* NUL-terminated "/system/bin/su". Shared constant used for comparison. */
extern const char su_path[];

/* Returns a __user pointer into current's user stack holding "/data/adb/ksud". */
char __user *ksud_user_path(void);

/* Returns a __user pointer into current's user stack holding an empty string. */
char __user *empty_user_path(void);

#endif
