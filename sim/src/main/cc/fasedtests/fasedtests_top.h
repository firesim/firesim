// See LICENSE for license details
#ifndef __FASED_TOP_H
#define __FASED_TOP_H

#include <memory>

#include "bridges/bridge_driver.h"
#include "bridges/fpga_model.h"
#include "firesim/systematic_scheduler.h"
#include "midasexamples/simif_peek_poke.h"

#include "bridges/synthesized_prints.h"

class fasedtests_top_t : public simif_peek_poke_t,
                         public systematic_scheduler_t,
                         public simulation_t {
public:
  fasedtests_top_t(const std::vector<std::string> &args, simif_t *simif);
  ~fasedtests_top_t() {}

  void simulation_init();
  void simulation_finish();
  int simulation_run();

protected:
  void add_bridge_driver(bridge_driver_t *bridge) {
    bridges.emplace_back(bridge);
  }
  void add_bridge_driver(FpgaModel *bridge) {
    fpga_models.emplace_back(bridge);
  }

private:
  // Memory mapped bridges bound to software models
  std::vector<std::unique_ptr<bridge_driver_t>> bridges;
  // FPGA-hosted models with programmable registers & instrumentation
  std::vector<std::unique_ptr<FpgaModel>> fpga_models;

  // profile interval: # of cycles to advance before profiling instrumentation
  // registers in models
  uint64_t profile_interval = -1;
  uint64_t profile_models();

  // Returns true if any bridge has signaled for simulation termination
  bool simulation_complete();
  // Returns the error code of the first bridge for which it is non-zero
  int exit_code();
};

#endif // __FASED_TOP_H
