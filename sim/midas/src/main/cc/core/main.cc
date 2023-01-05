// See LICENSE for license details.

#include <memory>

#include "core/config.h"
#include "core/simif.h"
#include "core/simulation.h"

#include "bridges/autocounter.h"
#include "bridges/clock.h"
#include "bridges/cpu_managed_stream.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/fpga_managed_stream.h"
#include "bridges/fpga_model.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

#ifdef PEEKPOKEBRIDGEMODULE_0_PRESENT
#include "bridges/peek_poke.h"
#endif
#ifdef BLOCKDEVBRIDGEMODULE_0_PRESENT
#include "bridges/blockdev.h"
#endif
#ifdef DROMAJOBRIDGEMODULE_0_PRESENT
#include "bridges/dromajo.h"
#endif
#ifdef GROUNDTESTBRIDGEMODULE_0_PRESENT
#include "bridges/groundtest.h"
#endif
#ifdef SERIALBRIDGEMODULE_0_PRESENT
#include "bridges/serial.h"
#endif
#ifdef SIMPLENICBRIDGEMODULE_0_PRESENT
#include "bridges/simplenic.h"
#endif
#ifdef TRACERVBRIDGEMODULE_0_PRESENT
#include "bridges/tracerv.h"
#endif
#ifdef UARTBRIDGEMODULE_0_PRESENT
#include "bridges/uart.h"
#endif

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
    #include "constructor.h"
    // DOC include end: Bridge Driver Registration
  }

  auto sim = create_simulation(args, *simif_ptr);
  return simif_ptr->run(*sim);
}
