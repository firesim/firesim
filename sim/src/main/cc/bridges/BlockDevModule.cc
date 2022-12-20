// See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "bridges/blockdev.h"
#include "simif_peek_poke.h"

class BlockDevModuleTest final : public simif_peek_poke_t, public simulation_t {
public:
  BlockDevModuleTest(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create),
        simulation_t(args) {
    blockdevs.emplace_back(
        new blockdev_t(simif,
                       args,
                       BLOCKDEVBRIDGEMODULE_0_num_trackers,
                       BLOCKDEVBRIDGEMODULE_0_latency_bits,
                       BLOCKDEVBRIDGEMODULE_0_substruct_create,
                       0));
    blockdevs.emplace_back(
        new blockdev_t(simif,
                       args,
                       BLOCKDEVBRIDGEMODULE_1_num_trackers,
                       BLOCKDEVBRIDGEMODULE_1_latency_bits,
                       BLOCKDEVBRIDGEMODULE_1_substruct_create,
                       1));
  }

  void simulation_init() override {
    // Initialise the blockdev bridge.
    for (auto &blockdev : blockdevs)
      blockdev->init();
  }

  int simulation_run() override {
    // Reset the device.
    poke(reset, 1);
    step(1);
    poke(reset, 0);
    step(1);

    // Tick until all requests are serviced.
    step(30000, false);
    for (unsigned i = 0; i < 3000 && !simif->done(); ++i) {
      for (auto &blockdev : blockdevs)
        blockdev->tick();
    }

    return EXIT_SUCCESS;
  }

  void simulation_finish() override {
    // Cleanup.
    for (auto &blockdev : blockdevs)
      blockdev->finish();
  }

private:
  std::vector<std::unique_ptr<blockdev_t>> blockdevs;
};

std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif) {
  return std::make_unique<BlockDevModuleTest>(args, simif);
}
