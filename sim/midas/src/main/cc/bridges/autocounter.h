#ifndef __AUTOCOUNTER_H
#define __AUTOCOUNTER_H

#include "core/address_map.h"
#include "core/bridge_driver.h"
#include "core/clock_info.h"
#include <fstream>
#include <vector>

// This will need to be manually incremented by descretion.
constexpr int autocounter_csv_format_version = 1;

struct AUTOCOUNTERBRIDGEMODULE_struct {
  uint64_t cycles_low;
  uint64_t cycles_high;
  uint64_t readrate_low;
  uint64_t readrate_high;
  uint64_t init_done;
  uint64_t countersready;
  uint64_t readdone;
};

class autocounter_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  struct Counter {
    std::string type;
    std::string event_label;
    std::string event_msg;
    uint32_t bit_width;
    uint32_t accumulator_width;
    uint32_t event_addr_hi;
    uint32_t event_addr_lo;
  };

  autocounter_t(simif_t &sim,
                AddressMap &&addr_map,
                const AUTOCOUNTERBRIDGEMODULE_struct &mmio_addrs,
                unsigned autocounterno,
                const std::vector<std::string> &args,
                const std::vector<Counter> &counters,
                const ClockInfo &clock_info);
  ~autocounter_t() override;

  void init() override;
  void tick() override;
  void finish() override;

private:
  const AUTOCOUNTERBRIDGEMODULE_struct mmio_addrs;
  const AddressMap addr_map;

  std::vector<Counter> counters;
  ClockInfo clock_info;

  uint64_t cur_cycle_base_clock = 0;
  uint64_t readrate;
  uint64_t readrate_base_clock;
  std::string autocounter_filename;
  std::ofstream autocounter_file;

  // Pulls a single sample from the Bridge, if available.
  // Returns true if a sample was read
  bool drain_sample();

  // Writes event autocounter metadata to the first lines of the output csv.
  void emit_autocounter_header();
};

#endif // __AUROCOUNTER_H
