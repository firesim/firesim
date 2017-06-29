// See LICENSE for license details.

#include "umi_dramsim2.h"
#include "umi.h"
#include <DRAMSim.h>
#include <iostream>
#include <fstream>
#include <list>
#include <queue>
#include <cstring>
#include <cstdlib>
#include <cassert>

//#define DEBUG_DRAMSIM2

using namespace DRAMSim;

void umi_dramsim2_t::read_complete(unsigned id, uint64_t address, uint64_t clock_cycle)
{
  resp.push(read_data.front());
  read_data.pop();
#ifdef DEBUG_DRAMSIM2
  fprintf(stderr, "[Callback] read complete: id=%d , addr=0x%lx , cycle=%lu\n", id, address, clock_cycle);
#endif
}

void umi_dramsim2_t::write_complete(unsigned id, uint64_t address, uint64_t clock_cycle)
{
#ifdef DEBUG_DRAMSIM2
  fprintf(stderr, "[Callback] write complete: id=%d , addr=0x%lx , cycle=%lu\n", id, address, clock_cycle);
#endif
}

void power_callback(double a, double b, double c, double d)
{
    //fprintf(stderr, "power callback: %0.3f, %0.3f, %0.3f, %0.3f\n",a,b,c,d);
}

void umi_dramsim2_t::init(size_t sz, int wsz, int lsz)
{
  assert(lsz == 64); // assumed by dramsim2
  umi_t::init(sz, wsz, lsz);

  dummy_data.resize(word_size);

  assert(size % (1024*1024) == 0);
  mem = getMemorySystemInstance("DDR3_micron_64M_8B_x4_sg15.ini", "system.ini", "dramsim2_ini", "results", size/(1024*1024));

  TransactionCompleteCB *read_cb = new Callback<umi_dramsim2_t, void, unsigned, uint64_t, uint64_t>(this, &umi_dramsim2_t::read_complete);
  TransactionCompleteCB *write_cb = new Callback<umi_dramsim2_t, void, unsigned, uint64_t, uint64_t>(this, &umi_dramsim2_t::write_complete);
  mem->RegisterCallbacks(read_cb, write_cb, power_callback);

#ifdef DEBUG_DRAMSIM2
  fprintf(stderr,"Dramsim2 init successful\n");
#endif
}

void umi_dramsim2_t::tick
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
      mem->addTransaction(true, req_addr);
      write(req_addr, (uint8_t*)req_data);
#ifdef DEBUG_DRAMSIM2
      fprintf(stderr, "Adding store transaction (addr=%lx; cyc=%ld)\n", req_addr, cycle);
#endif
    } else {
      mem->addTransaction(false, req_addr);
      read_data.push(read(req_addr));
#ifdef DEBUG_DRAMSIM2
      fprintf(stderr, "Adding load transaction (addr=%lx; cyc=%ld)\n", req_addr, cycle);
#endif
      
    }
  }

  if (resp_fire)
    resp.pop();

  mem->update();
  cycle++;

  if (reset) {
    while (!resp.empty()) resp.pop();
    cycle = 0;
  }
}
