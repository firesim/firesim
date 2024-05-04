#include "thread_pool.h"
#include "mem_pool.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

typedef threadpool_t<buffer_t*, std::string> buf_threadpool_t;

void fill_buffer(uint8_t* buf, size_t cnt, int val) {
  uint64_t* buf64 = (uint64_t*)buf;
  for (size_t i = 0; i < cnt/8; i++) {
    buf64[i] = val;
  }
}

int main() {
  int buf_cnt = 4;
  size_t max_input_sz = 4096;
  size_t buf_sz = max_input_sz * 2;
  int threads = 2;

  mempool_t* mempool = new mempool_t(buf_cnt, buf_sz, max_input_sz);
  buf_threadpool_t threadpool;
  threadpool.start(threads);

  for (int iter = 0; iter < 1000; iter++) {
    printf("iter: %d\n", iter);
    assert(!mempool->full());
    fill_buffer(mempool->next_empty(), max_input_sz, iter);
    mempool->fill(max_input_sz);

    if (mempool->full()) {
      while (mempool->next_buffer_full()) {
        ;
      }
      std::string outfile = "OUTFILE-" + std::to_string(iter) + ".log";
      threadpool.queue_job(print_buf, mempool->cur_buf(), outfile);
      mempool->advance_buffer();
    }
  }

  threadpool.stop();

  return 0;
}
