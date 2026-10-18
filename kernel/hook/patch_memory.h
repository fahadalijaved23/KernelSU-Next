/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 */

#ifndef __KSU_PATCH_MEMORY_H
#define __KSU_PATCH_MEMORY_H

#include <linux/types.h>
#include "linux/version.h"

#ifdef __aarch64__
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
#include "asm/text-patching.h" // IWYU pragma: keep
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 14, 0)
#include "asm/patching.h" // IWYU pragma: keep
#else
#include "asm/insn.h" // IWYU pragma: keep
#endif
#elif defined(__x86_64__) || defined(__riscv)
#include <asm/ptrace.h>
#else
#error "Unsupported arch"
#endif

#define KSU_PATCH_TEXT_FLUSH_DCACHE 1
#define KSU_PATCH_TEXT_FLUSH_ICACHE 2
/* Opt in to the Samsung RKP branch-patch route (arm64 only). Only safe for
 * hooks that do NOT need to invoke the original function from inside the
 * hook body - the syscall-table dispatcher install is the canonical use.
 * Hooks like selinux_hide/my_write_access that call `orig_fn(...)` must NOT
 * set this flag, or branch-patching will leave the saved orig pointer
 * pointing at a function whose first instruction jumps straight back into
 * the hook (stack-overflow recursion). On non-Samsung the flag is harmless;
 * ksu_rkp_key stays off and the stock patch path runs regardless. */
#define KSU_PATCH_TEXT_RKP_BRANCH 4

unsigned long phys_from_virt(unsigned long addr, int *err);
int ksu_patch_text(void *dst, void *src, size_t len, int flags);
void *scan_call_to(void *start, size_t size, void *target);

#endif
