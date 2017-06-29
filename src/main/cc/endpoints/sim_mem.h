#ifndef __SIM_MEM_H
#define __SIM_MEM_H

#include "endpoint.h"
#include "mm.h"
#include "mm_dramsim2.h"


static const size_t MEM_CHUNKS = MEM_DATA_BITS / (8 * sizeof(data_t));

struct sim_mem_data_t {
  struct {
    uint64_t addr;
    size_t id;
    size_t size;
    size_t len;
    bool valid;
    bool ready;
    bool fire() { return valid && ready; }
  } ar, aw;
  struct {
    data_t data[MEM_CHUNKS];
    size_t strb;
    bool last;
    bool valid;
    bool ready;
    bool fire() { return valid && ready; }
  } w;
  struct {
    data_t data[MEM_CHUNKS];
    size_t id;
    size_t resp;
    bool last;
    bool valid;
    bool ready;
    bool fire() { return valid && ready; }
  } r;
  struct {
    size_t id;
    size_t resp;
    bool valid;
    bool ready;
    bool fire() { return valid && ready; }
  } b;
};

class sim_mem_t: public endpoint_t
{
public:
  sim_mem_t(simif_t* s, int argc, char** argv);
  void delta(size_t t);
  void send(sim_mem_data_t& data);
  void recv(sim_mem_data_t& data);

  virtual void tick();
  virtual bool done();
  virtual bool stall();

  void write_mem(uint64_t addr, void* data);

private:
  mm_t* mem;
};

#endif // __SIM_MEM_H
