// See LICENSE for license details.

// Include the production top-level driver, whose class is defined in its .cc.
#include "fasedtests_top.cc"

#include <iostream>
#include <stdexcept>

namespace {
const TargetConfig config{};

class RegisterDevice : public simif_t {
public:
  RegisterDevice() : simif_t(config) {}
  uint32_t read(size_t address) override {
    if (address != 100)
      throw std::runtime_error("unexpected register read");
    return ++profile_reads;
  }
  void write(size_t, uint32_t) override {
    throw std::runtime_error("unexpected register write");
  }
  unsigned profile_reads = 0;
};
} // namespace

int main(int argc, char **argv) {
  try {
    if (argc != 2)
      throw std::runtime_error("expected one case name");
    const std::string name = argv[1];
    const bool profile = name == "profile-lifetime";
    if (!profile && name != "no-profile")
      throw std::runtime_error("unknown case: " + name);
    std::vector<std::string> args{"+max-cycles=21"};
    if (profile)
      args.emplace_back("+profile-interval=7");
    RegisterDevice device;
    widget_registry_t registry;
    registry.add_widget(new clockmodule_t(device, {}, 0, {}));
    registry.add_widget(new peek_poke_t(device, {}, 0, {}, {}, {}));
    registry.add_widget(new FASEDMemoryTimingModel(
        device, AddressMap({{"cycles", 100}}, {}), 0, {}, "stats.csv", 4096));
    fasedtests_top_t simulation(device, registry, args);
    // The constructor and its local profile_interval have returned. Exercise
    // the actual registered callback repeatedly rather than copying its body.
    const unsigned iterations = profile ? 3 : 1;
    for (unsigned i = 0; i < iterations; ++i) {
      simulation.run_scheduled_tasks();
      const auto step = simulation.get_largest_stepsize();
      if (step != (profile ? 7 : 21))
        throw std::runtime_error("incorrect profiling interval");
    }
    if (!simulation.finished_scheduled_tasks())
      throw std::runtime_error("scheduler did not reach cycle horizon");
    if (device.profile_reads != (profile ? 3 : 0))
      throw std::runtime_error("incorrect profiling read count");
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
  }
}
