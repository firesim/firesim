// See LICENSE for license details.

#ifndef __FPGA_MODEL_H
#define __FPGA_MODEL_H

#include "simif.h"
#include "address_map.h"

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

class FpgaModel
{
private:
  simif_t *sim;

public:
  FpgaModel(simif_t* s, AddressMap addr_map): sim(s), addr_map(addr_map) {};
  virtual void init() = 0;
  virtual void profile() = 0;
  virtual void finish() = 0;

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

  uint64_t read64(std::string msw, std::string lsw, data_t upper_word_mask) {
    assert(sizeof(data_t) == 4);
    uint64_t data = ((uint64_t) (read(msw) & upper_word_mask)) << 32;
    return data | read(lsw);
  }

};

#endif // __FPGA_MODEL_H
