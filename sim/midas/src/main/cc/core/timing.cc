// See LICENSE for license details.

#include "timing.h"

#include <sys/time.h>

midas_time_t timestamp() {
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

double diff_secs(midas_time_t end, midas_time_t start) {
  return ((double)(end - start)) / TIME_DIV_CONST;
}
