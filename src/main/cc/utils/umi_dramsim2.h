// See LICENSE for license details.

#ifndef _UMI_EMULATOR_DRAMSIM2_H
#define _UMI_EMULATOR_DRAMSIM2_H

#include "umi.h"
#include <DRAMSim.h>
#include <queue>
#include <vector>
#include <stdint.h>

class umi_dramsim2_t : public umi_t
{
 public:
  umi_dramsim2_t() { }

  virtual void init(size_t sz, int word_size, int line_size);

  virtual bool req_ready() { return mem->willAcceptTransaction(); }

  virtual void tick
  (
    bool reset,
    bool req_valid,
    bool req_wr,
    uint64_t req_addr,
    void* req_data,
    bool resp_ready
  );

 protected:
  std::queue<std::vector<char>> read_data;
  DRAMSim::MultiChannelMemorySystem *mem;
  void read_complete(unsigned id, uint64_t address, uint64_t clock_cycle);
  void write_complete(unsigned id, uint64_t address, uint64_t clock_cycle);
};

#endif
