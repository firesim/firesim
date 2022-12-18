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
  AddressMap addr_map;
  std::unordered_map<std::string, uint32_t> expected_uarchevent_values;

public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  /**
   * @param addr_map This matches the addr map pass to the FASED timing model
   */
  test_harness_bridge_t(simif_t &simif,
                        peek_poke_t &peek_poke,
                        AddressMap addr_map,
                        const std::vector<std::string> &args);
  virtual ~test_harness_bridge_t(){};
  virtual void init(){};
  virtual void tick();
  virtual bool terminate() { return done || error != 0; };
  virtual int exit_code() { return error; };
  virtual void finish(){};
};

#endif // __TEST_HARNESS_BRIDGE_H
