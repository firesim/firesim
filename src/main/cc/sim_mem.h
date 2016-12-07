#ifndef __SIM_MEM_H
#define __SIM_MEM_H

#include "simif.h"
#include "mm.h"
#include "mm_dramsim2.h"

// TODO: support multi channels

class sim_mem_t
{
public:
  sim_mem_t(simif_t* s, int argc, char** argv);
  void init();
  void tick();

private:
  simif_t* sim;
  mm_t* mem;
  size_t latency;
};

#endif // __SIM_MEM_H
