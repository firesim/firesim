// See LICENSE for license details.

#ifndef UMI_EMULATOR_H
#define UMI_EMULATOR_H

#include "mm.h"
#include <stdint.h>
#include <cstring>
#include <queue>

class umi_t: public mm_base_t
{
 public:
  umi_t() { }

  virtual void init(size_t sz, int wsz, int lsz);
  virtual bool req_ready() { return true; }
  virtual bool resp_valid() { return !resp.empty(); }
  virtual void* resp_data() { return resp_valid() ? &resp.front()[0] : &dummy_data[0]; }

  virtual void tick
  (
    bool reset,
    bool req_valid,
    bool req_wr,
    uint64_t req_addr,
    void* req_data,
    bool resp_ready
  ) = 0;

protected:
  uint64_t cycle;
  std::vector<char> dummy_data;
  std::queue<std::vector<char>> resp;
};

class umi_magic_t : public umi_t
{
 public:
  umi_magic_t() { }

  virtual void tick
  (
    bool reset,
    bool req_valid,
    bool req_wr,
    uint64_t req_addr,
    void* req_data,
    bool resp_ready
  );
};
#endif
