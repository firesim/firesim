// See LICENSE for license details.

#include "bridges/clock.h"
#include "bridges/master.h"
#include "core/bridge_driver.h"
#include "core/config.h"
#include "core/simulation.h"

#include <iostream>
#include <map>
#include <stdexcept>

namespace {
const TargetConfig config{};

class RegisterDevice : public simif_t {
public:
  RegisterDevice() : simif_t(config) {}
  uint32_t init_value = 1;
  unsigned init_reads = 0;
  unsigned pending_reads = 0;
  unsigned other_reads = 0;
  unsigned writes = 0;
  uint32_t read(size_t address) override {
    if (address == 0) {
      ++init_reads;
      if (init_reads <= pending_reads)
        return 0;
      return init_value;
    }
    ++other_reads;
    if (address == 4)
      return 0x46697265;
    if (address == 16 || address == 28)
      return 1;
    return 0;
  }
  void write(size_t, uint32_t) override { ++writes; }
};

class FinalizingBridge : public bridge_driver_t {
public:
  static char KIND;
  FinalizingBridge(simif_t &device, bool terminates, int code)
      : bridge_driver_t(device, &KIND), terminates(terminates), code(code) {}
  void finish() override { finished = true; }
  bool terminate() override { return finished && terminates; }
  int exit_code() override { return finished ? code : 0; }
  bool finished = false;

private:
  bool terminates;
  int code;
};
char FinalizingBridge::KIND;

class Simulation : public simulation_t {
public:
  Simulation(widget_registry_t &registry, int code, bool timeout,
             const std::vector<std::string> &args = {})
      : simulation_t(registry, args), code(code), timeout(timeout) {}
  int simulation_run() override { return code; }
  bool simulation_timed_out() override { return timeout; }

private:
  int code;
  bool timeout;
};
} // namespace

int main(int argc, char **argv) {
  try {
    if (argc != 2)
      throw std::runtime_error("expected one case name");
    const std::string name = argv[1];
    if (name == "init-pending" || name == "init-invalid" ||
        name == "init-unavailable") {
      RegisterDevice device;
      device.init_value = name == "init-pending"   ? 0
                          : name == "init-invalid" ? 2
                                                   : UINT32_MAX;
      master_t master(device, {0, 4, 8}, 0, {});
      bool rejected = false;
      try {
        if (master.is_init_done())
          throw std::logic_error("Invalid initialization reported ready");
      } catch (const std::runtime_error &) {
        rejected = true;
      }
      if (rejected != (name != "init-pending") || device.init_reads != 1)
        throw std::runtime_error(
            "Initialization must reject invalid values after one read");
      return 0;
    }
    const std::map<std::string, std::vector<std::string>> timeout_args = {
        {"init-timeout-zero", {"+init-timeout-ms=0"}},
        {"init-timeout-negative", {"+init-timeout-ms=-1"}},
        {"init-timeout-overflow", {"+init-timeout-ms=4294967296"}},
        {"init-timeout-upper", {"+init-timeout-ms=600001"}},
        {"init-timeout-empty", {"+init-timeout-ms="}},
        {"init-timeout-trailing", {"+init-timeout-ms=20ms"}},
        {"init-timeout-plus", {"+init-timeout-ms=+20"}},
        {"init-timeout-space", {"+init-timeout-ms= 20"}},
        {"init-timeout-duplicate",
         {"+init-timeout-ms=20", "+init-timeout-ms=20"}},
        {"init-timeout-min", {"+init-timeout-ms=1"}},
        {"init-timeout-max", {"+init-timeout-ms=600000"}},
    };
    if (timeout_args.count(name) || name == "init-wait-timeout" ||
        name == "init-wait-ready" || name == "init-wait-invalid") {
      RegisterDevice device;
      widget_registry_t registry;
      registry.add_widget(
          new clockmodule_t(device, {16, 20, 24, 28, 32, 36}, 0, {}));
      registry.add_widget(new master_t(device, {0, 4, 8}, 0, {}));
      if (timeout_args.count(name)) {
        bool rejected = false;
        try {
          Simulation simulation(registry, 0, false, timeout_args.at(name));
        } catch (const std::invalid_argument &) {
          rejected = true;
        }
        const bool valid = name == "init-timeout-min" || name == "init-timeout-max";
        if (rejected == valid || device.init_reads || device.other_reads || device.writes)
          throw std::runtime_error("Invalid initialization deadline parsing or early MMIO");
        return 0;
      }
      device.pending_reads = name == "init-wait-ready" ? 5 : 2;
      device.init_value = name == "init-wait-timeout" ? 0
                          : name == "init-wait-invalid" ? UINT32_MAX : 1;
      Simulation simulation(registry, 0, false,
                            {name == "init-wait-ready" ? "+init-timeout-ms=1000"
                                                       : "+init-timeout-ms=20"});
      const auto start = std::chrono::steady_clock::now();
      std::string error;
      try {
        if (simulation.execute_simulation_flow() != 0)
          throw std::logic_error("Unexpected simulation failure");
      } catch (const std::runtime_error &e) {
        error = e.what();
      }
      const auto elapsed = std::chrono::steady_clock::now() - start;
      if (elapsed > std::chrono::seconds(2))
        throw std::runtime_error("Initialization exceeded test deadline");
      if (name == "init-wait-timeout") {
        if (error != "Simulation master initialization timed out" ||
            elapsed < std::chrono::milliseconds(20) || device.init_reads > 21)
          throw std::runtime_error("Pending initialization must stop with bounded polling");
      } else if (name == "init-wait-invalid") {
        if (error.find("Invalid simulation master INIT_DONE value:") != 0 ||
            device.init_reads != 3)
          throw std::runtime_error("Invalid initialization must stop immediately");
      } else {
        if (!error.empty() || device.init_reads != 6 ||
            elapsed < std::chrono::milliseconds(5))
          throw std::runtime_error("Delayed initialization did not complete with paced polling");
        return 0;
      }
      if (device.other_reads || device.writes)
        throw std::runtime_error("Initialization failure must not access later registers");
      return 0;
    }
    int run_code = 0, first_code = 0, second_code = 0, expected = 0;
    bool terminates = true, timeout = false;
    if (name == "late-failure") {
      first_code = expected = 123;
    } else if (name == "early-failure") {
      run_code = expected = 17;
      first_code = 123;
    } else if (name == "multiple-failures") {
      first_code = expected = 123;
      second_code = 124;
    } else if (name == "success-before-failure") {
      second_code = expected = 124;
    } else if (name == "nonterminating") {
      terminates = false;
      first_code = 123;
    } else if (name == "timeout") {
      timeout = true;
      expected = EXIT_FAILURE;
    } else if (name != "success") {
      throw std::runtime_error("unknown case: " + name);
    }
    RegisterDevice device;
    widget_registry_t registry;
    registry.add_widget(
        new clockmodule_t(device, {16, 20, 24, 28, 32, 36}, 0, {}));
    registry.add_widget(new master_t(device, {0, 4, 8}, 0, {}));
    auto *first = new FinalizingBridge(device, terminates, first_code);
    auto *second = new FinalizingBridge(device, true, second_code);
    registry.add_widget(first);
    registry.add_widget(second);
    Simulation simulation(registry, run_code, timeout);
    const int actual = simulation.execute_simulation_flow();
    if (!first->finished || !second->finished)
      throw std::runtime_error("not all bridges were finalized");
    if (actual != expected)
      throw std::runtime_error("expected exit code " +
                               std::to_string(expected) + ", got " +
                               std::to_string(actual));
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
  }
}
