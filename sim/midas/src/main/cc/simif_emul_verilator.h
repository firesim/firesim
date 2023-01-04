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

  ~simif_emul_verilator_t();

  int run();

  uint64_t get_time() const { return main_time; }

private:
  void tick();

private:
  uint64_t main_time = 0;

  std::unique_ptr<Vemul> top;

#if VM_TRACE
  std::unique_ptr<VerilatedVcdC> tfp;
#endif
};

#endif // __SIMIF_EMUL_VERILATOR_H
