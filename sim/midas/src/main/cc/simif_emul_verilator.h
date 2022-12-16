// See LICENSE for license details.

#ifndef __SIMIF_EMUL_VERILATOR_H
#define __SIMIF_EMUL_VERILATOR_H

#include <verilated.h>
#ifdef VM_TRACE
#include <verilated_vcd_c.h>
#endif

#include "simif_emul.h"

/**
 * Verilator-specific metasimulator implementation.
 */
class simif_emul_verilator_t final : public simif_emul_t {
public:
  simif_emul_verilator_t(const std::vector<std::string> &args);

  ~simif_emul_verilator_t() {}

  void sim_init() override;

  void finish() override;

  void advance_target() override;

private:
  void tick();

private:
  std::unique_ptr<Vverilator_top> top;
#ifdef VM_TRACE
  std::unique_ptr<VerilatedVcdC> tfp;
#endif
};

#endif // __SIMIF_EMUL_VERILATOR_H
