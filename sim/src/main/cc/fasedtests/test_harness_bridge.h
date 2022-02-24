//See LICENSE for license details.

#ifndef __TEST_HARNESS_BRIDGE_H
#define __TEST_HARNESS_BRIDGE_H

#include <unordered_map>

// Depend on standard simif once this bridge is modified to not use peek-poke
#include "midasexamples/simif_peek_poke.h"

// From MIDAS
#include "bridges/bridge_driver.h"
#include "bridges/address_map.h"

class test_harness_bridge_t: public bridge_driver_t
{
  private:
    int error = 0;
    bool done = false;
    simif_peek_poke_t * sim;
    AddressMap addr_map;
    std::unordered_map<std::string, uint32_t> expected_uarchevent_values;

  public:
    test_harness_bridge_t(simif_peek_poke_t* sim, AddressMap addr_map, const std::vector<std::string>& args);
    virtual ~test_harness_bridge_t() {};
    virtual void init() {};
    virtual void tick();
    virtual bool terminate() { return done || error != 0; };
    virtual int exit_code() { return error; };
    virtual void finish() {};
};

#endif // __TEST_HARNESS_BRIDGE_H
