// See LICENSE for license details.

#ifndef __TIMING_H
#define __TIMING_H

#include <cstdint>

#define TIME_DIV_CONST 1000000.0

using midas_time_t = uint64_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

#endif // __TIMING_H
