// See LICENSE for license details.

#include "simif.h"
#include "core/config.h"
#include "core/simulation.h"
#include "core/stream_engine.h"

#include <iostream>

simif_t::simif_t(const TargetConfig &config,
                 const std::vector<std::string> &args)
    : config(config), args(args), registry() {
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

simif_t::~simif_t() = default;

CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() {
  std::cerr << "CPU-managed streams are not supported" << std::endl;
  abort();
}

FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() {
  std::cerr << "FPGA-managed streams are not supported" << std::endl;
  abort();
}

void simif_t::target_init() {
  auto &master = registry.get_widget<master_t>();
  auto &loadmem = registry.get_widget<loadmem_t>();

  // Do any post-constructor initialization required before requesting MMIO
  while (!master.is_init_done())
    ;

  if (auto *stream = registry.get_stream_engine()) {
    stream->init();
  }

  if (do_zero_out_dram) {
    fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few seconds...\n");
    loadmem.zero_out_dram();
  }

  if (!fastloadmem && !load_mem_path.empty()) {
    loadmem.load_mem_from_file(load_mem_path);
  }
}

std::string_view simif_t::get_target_name() const { return config.target_name; }

int simif_t::run(simulation_t &sim) {
  target_init();
  return sim.execute_simulation_flow();
}
