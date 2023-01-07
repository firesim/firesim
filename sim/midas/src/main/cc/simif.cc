// See LICENSE for license details.

#include "simif.h"
#include "bridges/simulation.h"
#include "bridges/stream_engine.h"

extern std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif);

simif_t::simif_t(const TargetConfig &config,
                 const std::vector<std::string> &args)
    : config(config), loadmem(this,
                              LOADMEMWIDGET_0_substruct_create,
                              config.mem,
                              LOADMEMWIDGET_0_mem_data_chunk),
      clock(this, CLOCKBRIDGEMODULE_0_substruct_create),
      master(this, SIMULATIONMASTER_0_substruct_create),
      sim(create_simulation(args, this)) {
  for (auto &arg : args) {
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+loadmem=") == 0) {
      load_mem_path = arg.c_str() + 9;
    }
    if (arg.find("+zero-out-dram") == 0) {
      do_zero_out_dram = true;
    }
  }
}

simif_t::~simif_t() {}

void simif_t::target_init() {
  // Do any post-constructor initialization required before requesting MMIO
  while (!master.is_init_done())
    ;

  if (!fastloadmem && !load_mem_path.empty()) {
    loadmem.load_mem_from_file(load_mem_path);
  }

  if (managed_stream) {
    managed_stream->init();
  }
}

int simif_t::simulation_run() {
  target_init();

  if (do_zero_out_dram) {
    fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few seconds...\n");
    loadmem.zero_out_dram();
  }

  return sim->execute_simulation_flow();
}
