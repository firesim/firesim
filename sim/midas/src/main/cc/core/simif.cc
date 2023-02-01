// See LICENSE for license details.

#include "simif.h"
#include "core/config.h"
#include "core/simulation.h"

#include <iostream>

simif_t::simif_t(const TargetConfig &config) : config(config) {}

simif_t::~simif_t() = default;

CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() {
  std::cerr << "CPU-managed streams are not supported" << std::endl;
  abort();
}

FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() {
  std::cerr << "FPGA-managed streams are not supported" << std::endl;
  abort();
}

std::string_view simif_t::get_target_name() const { return config.target_name; }

int simif_t::run(simulation_t &sim) { return sim.execute_simulation_flow(); }
