#ifndef __LOADMEM_M_H
#define __LOADMEM_M_H

#include "simif.h"
#include "endpoints/address_map.h"

/**
 * Base class for (handwritten) FPGA-hosted models
 *
 * These models have two important methods:
 *
 * 1) init: Which sets their runtime configuration. Ex. The latency of
 * latency pipe
 *
 * 2) profile: Which gives a default means to read all readable registers in
 * the model, including programmable registers and instrumentation.
 *
 */

class loadmem_m
{
private:
  simif_t *sim;

public:
  loadmem_m(simif_t* s, AddressMap addr_map): sim(s), addr_map(addr_map) {};
  void load_mem(std::string filename);
  void read_mem(size_t addr, mpz_t& value);
  void write_mem(size_t addr, mpz_t& value);

protected:
  AddressMap addr_map;

  void write(size_t addr, data_t data) {
    sim->write(addr, data);
  }

  data_t read(size_t addr) {
    return sim->read(addr);
  }

  void write(std::string reg, data_t data){
    sim->write(addr_map.w_addr(reg), data);
  }

  data_t read(std::string reg){
    return sim->read(addr_map.r_addr(reg));
  }

};

#endif // __LOADMEM_M_H
