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
  inline void step(size_t t) {
#ifdef NASTIWIDGET_0
    sim->write(NASTIWIDGET_0(steps), t);
#endif
  }
  inline bool stall() {
#ifdef NASTIWIDGET_0
    return sim->read(NASTIWIDGET_0(stall));
#else
    return false;
#endif
  }
  inline bool target_fire() {
#ifdef NASTIWIDGET_0
    return sim->read(NASTIWIDGET_0(tfire));
#else
    return false;
#endif
  }

private:
  simif_t* sim;
  mm_t* mem;
  size_t latency;
};

#endif // __SIM_MEM_H
