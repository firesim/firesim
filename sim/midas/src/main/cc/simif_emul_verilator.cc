// See LICENSE for license details.

#include <signal.h>

#include <verilated.h>
#ifdef VM_TRACE
#include <verilated_vcd_c.h>
#endif

#include "emul/mm.h"
#include "emul/mmio.h"
#include "emul/simif_emul.h"

/**
 * Verilator-specific metasimulator implementation.
 */
class simif_emul_verilator_t final : public simif_emul_t {
public:
  simif_emul_verilator_t(const TargetConfig &config,
                         const std::vector<std::string> &args);

  ~simif_emul_verilator_t();

  int run(simulation_t &sim);

  uint64_t get_time() const { return main_time; }

private:
  uint64_t main_time = 0;

  void tick();

  std::unique_ptr<Vemul> top;
#ifdef VM_TRACE
  std::unique_ptr<VerilatedVcdC> tfp;
#endif
};

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

int simif_emul_verilator_t::run(simulation_t &sim) {
  start_driver(sim);

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

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_emul_verilator_t>(config, args);
}
