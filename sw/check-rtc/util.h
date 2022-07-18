// See LICENSE for license details.

#ifndef __UTIL_H
#define __UTIL_H

//--------------------------------------------------------------------------
// Macros

// Set HOST_DEBUG to 1 if you are going to compile this for a host
// machine (ie Athena/Linux) for debug purposes and set HOST_DEBUG
// to 0 if you are compiling with the smips-gcc toolchain.

#ifndef HOST_DEBUG
#define HOST_DEBUG 0
#endif

// Set PREALLOCATE to 1 if you want to preallocate the benchmark
// function before starting stats. If you have instruction/data
// caches and you don't want to count the overhead of misses, then
// you will need to use preallocation.

#ifndef PREALLOCATE
#define PREALLOCATE 0
#endif

// Set SET_STATS to 1 if you want to carve out the piece that actually
// does the computation.

#if HOST_DEBUG
#include <stdio.h>
static void setStats(int enable) {}
#else
extern void setStats(int enable);
#endif

#include <stdint.h>

#define static_assert(cond)                                                    \
  switch (0) {                                                                 \
  case 0:                                                                      \
  case !!(long)(cond):;                                                        \
  }

static void __attribute__((noinline)) barrier(int ncores) {
  static volatile int sense;
  static volatile int count;
  static __thread int threadsense;

  __sync_synchronize();

  threadsense = !threadsense;
  if (__sync_fetch_and_add(&count, 1) == ncores - 1) {
    count = 0;
    sense = threadsense;
  } else
    while (sense != threadsense)
      ;

  __sync_synchronize();
}

#ifdef __riscv
#include "encoding.h"
#endif

#define stringify_1(s) #s
#define stringify(s) stringify_1(s)
#define stats(code, iter)                                                      \
  do {                                                                         \
    unsigned long _c = -read_csr(mcycle), _i = -read_csr(minstret);            \
    code;                                                                      \
    _c += read_csr(mcycle), _i += read_csr(minstret);                          \
    if (cid == 0)                                                              \
      printf("\n%s: %ld cycles, %ld.%ld cycles/iter, %ld.%ld CPI\n",           \
             stringify(code),                                                  \
             _c,                                                               \
             _c / iter,                                                        \
             10 * _c / iter % 10,                                              \
             _c / _i,                                                          \
             10 * _c / _i % 10);                                               \
  } while (0)

#endif //__UTIL_H
