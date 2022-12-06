// See LICENSE for license details.

#include <memory>

#include "core/config.h"
#include "core/simif.h"
#include "core/simulation.h"

#define GET_INCLUDES
#include "FireSim-generated.const.h"
#undef GET_INCLUDES

#define GET_METASIM_INTERFACE_CONFIG
#define GET_MEMORY_OFFSET
#define GET_SUBSTRUCT_CHECKS
#include "FireSim-generated.const.h"
#undef GET_SUBSTRUCT_CHECKS

// The user-defined part of the driver implements this method to return
// a simulation instance implementing all simulation-specific logic.
extern std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t &simif);

// The platform-specific component of the driver implements this method
// to return a handle to the simulation.
extern std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv);

// clang-format off

// Entry point of the driver.
int main(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  auto simif_ptr = create_simif(conf_target, argc, argv);

  {
    auto &simif = *simif_ptr;
    auto &registry = simif_ptr->get_registry();

    // DOC include start: Bridge Driver Registration
    // Here we instantiate our driver once for each bridge in the target
    // Golden Gate emits a <BridgeModuleClassName>_<id>_PRESENT macro for each
    // instance which you may use to conditionally instantiate your driver.
    // This file can be included in the setup method of any top-level to pass
    // an instance of each driver to the `add_bridge_driver` method. Drivers can
    // be distinguished by overloading the method with the appropriate type.

    // The different macros ensure that widgets that are required by other
    // bridges are initialized in the correct order: the master, clock and
    // loadmem widgets are built first, followed by stream engines and the
    // rest of the bridges of the design, which can make use of widgets.
    #define GET_CORE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_CORE_CONSTRUCTOR
    #define GET_MANAGED_STREAM_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_MANAGED_STREAM_CONSTRUCTOR
    #define GET_BRIDGE_CONSTRUCTOR
    #include "FireSim-generated.const.h"
    #undef GET_BRIDGE_CONSTRUCTOR
    // DOC include end: Bridge Driver Registration
  }

  auto sim = create_simulation(args, *simif_ptr);
  return simif_ptr->run(*sim);
}
