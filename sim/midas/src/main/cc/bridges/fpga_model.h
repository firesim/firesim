// See LICENSE for license details.

#ifndef __FPGA_MODEL_H
#define __FPGA_MODEL_H

#include "core/address_map.h"
#include "core/simif.h"

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

class FpgaModel {
private:
  simif_t *sim;

public:
  FpgaModel(simif_t *s, const AddressMap &addr_map)
      : sim(s), addr_map(addr_map) {}
  virtual ~FpgaModel() = default;

  virtual void init() = 0;
  virtual void profile() = 0;
  virtual void finish() = 0;

  const AddressMap &get_addr_map() const { return addr_map; }

protected:
  const AddressMap addr_map;

  void write(size_t addr, uint32_t data) { sim->write(addr, data); }

  uint32_t read(size_t addr) { return sim->read(addr); }

  void write(const std::string &reg, uint32_t data) {
    sim->write(addr_map.w_addr(reg), data);
  }

  uint32_t read(const std::string &reg) {
    return sim->read(addr_map.r_addr(reg));
  }

  uint64_t read64(const std::string &msw,
                  const std::string &lsw,
                  uint32_t upper_word_mask) {
    uint64_t data = ((uint64_t)(read(msw) & upper_word_mask)) << 32;
    return data | read(lsw);
  }
};

#endif // __FPGA_MODEL_H
