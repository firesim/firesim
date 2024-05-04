#ifndef __MEM_POOL_H__
#define __MEM_POOL_H__

#include <stdlib.h>
#include <inttypes.h>
#include <vector>

class buffer_t {
public:
  buffer_t(size_t sz, size_t max_input_sz);
  ~buffer_t();

  bool almost_full();
  void clear();
  uint8_t* next_empty();
  void fill(size_t amount);
  uint8_t* get_data();
  size_t   bytes();

private:
  size_t max_input_sz;
  size_t sz;
  size_t offset;
  uint8_t* data;
};

class mempool_t {
public:
  mempool_t(int buf_cnt, size_t buf_sz, size_t max_input_sz); 
  ~mempool_t();

  bool full();
  uint8_t* next_empty();
  void fill(size_t amount);
  buffer_t* cur_buf();
  volatile bool next_buffer_full();
  void advance_buffer();

private:
  int count;
  int head;
  std::vector<buffer_t*> buffers;
};

#endif //__MEM_POOL_H__


// assert(!mempool->full());
// 
// buf = mempool->cur_buf();
// rec = pull(buff ...)
// mempool->fill(rec)
//
// if (mempool->full()) {
//   while (mempool->next_buffer_full()) {
//      ;
//   }
//   thread_pool->queue_job(print_insn_logs, mempool->cur_buf(), out_filename);
//   mempool->advance_buffer();
// }
