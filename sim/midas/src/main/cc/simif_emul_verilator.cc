// See LICENSE for license details.

#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif

#include "simif_emul_verilator.h"

/// Simulator instance used by DPI.
simif_emul_verilator_t *simulator = nullptr;

double sc_time_stamp() { return simulator->get_time(); }

simif_emul_verilator_t::simif_emul_verilator_t(
    const TargetConfig &config, const std::vector<std::string> &args)
    : simif_emul_t(config, args), top(std::make_unique<Vemul>()) {
  simulator = this;

#if VM_TRACE
  tfp.reset(new VerilatedVcdC);
  Verilated::traceEverOn(true);
  VL_PRINTF("Enabling waves: %s\n", waveform.c_str());
  top->trace(tfp.get(), 99);
  tfp->open(waveform.c_str());
#endif
}

simif_emul_verilator_t::~simif_emul_verilator_t() {
#if VM_TRACE
  if (tfp)
    tfp->close();
#endif
}

int simif_emul_verilator_t::run() {
  top->clock = 0;

  top->reset = 1;
  for (unsigned i = 0; i < 10; ++i) {
    tick();
  }
  top->reset = 0;

  while (!top->fin) {
    tick();
  }

  return end();
}

void simif_emul_verilator_t::tick() {
  top->eval();

#if VM_TRACE
  if (tfp)
    tfp->dump((double)main_time);
#endif // VM_TRACE
  main_time++;
  top->clock = 1;
  top->eval();

#if VM_TRACE
  if (tfp)
    tfp->dump((double)main_time);
#endif // VM_TRACE
  main_time++;
  top->clock = 0;
  top->eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  std::vector<std::string> args(argv + 1, argv + argc);
  return simif_emul_verilator_t(conf_target, args).run();
}
