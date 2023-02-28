// See LICENSE for license details.

#ifndef __TEST_HARNESS_BRIDGE_H
#define __TEST_HARNESS_BRIDGE_H

#include <unordered_map>

#include "bridges/peek_poke.h"
#include "core/address_map.h"
#include "core/bridge_driver.h"

class test_harness_bridge_t : public bridge_driver_t {
private:
  int error = 0;
  bool done = false;
  peek_poke_t &peek_poke;
  const std::vector<FASEDMemoryTimingModel *> models;
  std::unordered_map<std::string, uint32_t> expected_uarchevent_values;

public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  /**
   * @param addr_map This matches the addr map pass to the FASED timing model
   */
  test_harness_bridge_t(simif_t &simif,
                        peek_poke_t &peek_poke,
                        const std::vector<FASEDMemoryTimingModel *> &models,
                        const std::vector<std::string> &args);
  ~test_harness_bridge_t() override = default;

  /**
   * Checks the functionality of memory models.
   *
   * This periodically peeks a done bit on the DUT. After it's been asserted,
   * it then reads uarch event counts from the FASED instance and compares them
   * against expected values.
   */
  void tick() override;

  bool terminate() override { return done || error != 0; };
  int exit_code() override { return error; };
};

#endif // __TEST_HARNESS_BRIDGE_H
