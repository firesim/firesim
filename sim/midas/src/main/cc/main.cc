// See LICENSE for license details.

#include <memory>

#include "core/config.h"
#include "core/simif.h"
#include "core/simulation.h"

#define GET_INCLUDES
#include "FireSim-generated.const.h"
#undef GET_INCLUDES

#define GET_METASIM_INTERFACE_CONFIG
#define GET_SUBSTRUCT_CHECKS
#include "FireSim-generated.const.h"
#undef GET_SUBSTRUCT_CHECKS

// The user-defined part of the driver implements this method to return
// a simulation instance implementing all simulation-specific logic.
extern std::unique_ptr<simulation_t>
create_simulation(simif_t &simif,
                  widget_registry_t &registry,
                  const std::vector<std::string> &args);

// The platform-specific component of the driver implements this method
// to return a handle to the simulation.
extern std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv);

// Entry point of the driver.
int main(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);

  // Create the hardware interface.
  auto simif_ptr = create_simif(conf_target, argc, argv);
  auto &simif = *simif_ptr;

  // clang-format off
  widget_registry_t registry;
  {
    // Here we instantiate our driver once for each bridge in the target. The
    // generated header contains a list of constructor calls. The different
    // macros ensure that widgets that are required by other bridges are
    // initialized in the correct order: the master, clock and loadmem widgets
    // are built first, followed by stream engines and the rest of the bridges
    // of the design, which can make use of widgets.
    #define GET_CORE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_CORE_CONSTRUCTOR
    #define GET_MANAGED_STREAM_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_MANAGED_STREAM_CONSTRUCTOR
    #define GET_BRIDGE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_BRIDGE_CONSTRUCTOR
  }
  // clang-format on

  // Create the simulation instance.
  auto sim = create_simulation(simif, registry, args);

  // Run the simulation with the given implementation.
  return simif.run(*sim);
}
