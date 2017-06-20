// See LICENSE for license details.

#include "umi.h"

void umi_t::init(size_t sz, int wsz, int lsz)
{
  mm_base_t::init(sz, wsz, lsz);
  dummy_data.resize(word_size);
}

void umi_magic_t::tick
(
  bool reset,
  bool req_valid,
  bool req_wr,
  uint64_t req_addr,
  void* req_data,
  bool resp_ready
)
{
  bool req_fire = req_valid && req_ready();
  bool resp_fire = resp_valid() && resp_ready;

  if (req_fire)
  {
    if (req_wr) {
      write(req_addr, (uint8_t*)req_data);
    } else {
      resp.push(read(req_addr));
    }
  }

  if (resp_fire)
    resp.pop();

  cycle++;

  if (reset) {
    while (!resp.empty()) resp.pop();
    cycle = 0;
  }
}
