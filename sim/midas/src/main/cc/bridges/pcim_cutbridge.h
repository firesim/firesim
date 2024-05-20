#ifndef __PCIM_CUTBOUNDARY_H__
#define __PCIM_CUTBOUNDARY_H__

#include <cstdint>
#include <vector>

#include "core/bridge_driver.h"
#include "core/stream_engine.h"

struct PCIMCUTBOUNDARYBRIDGEMODULE_struct {
  uint64_t input_tokens;
  uint64_t output_tokens;
  uint64_t tokenOutQueue_io_count;
  uint64_t tokenInQueue_io_count;
  uint64_t cutInQueue_io_count;
  uint64_t cutOutQueue_io_count;
  uint64_t to_host_fire_count;
  uint64_t from_host_fire_count;
  uint64_t assert_to_host_eq;
  uint64_t assert_from_host_eq;
  uint64_t init_simulator_tokens;
  uint64_t init_simulator_tokens_valid;
  uint64_t cur_init_tokens;
  uint64_t comb_init_tokens;
  uint64_t garbage_rx_cnt;
};

class pcim_cutbridge_t final : public streaming_bridge_driver_t {
public:
  static char KIND;

  pcim_cutbridge_t(simif_t &sim,
                   StreamEngine &stream,
                   const PCIMCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
                   int idx,
                   const std::vector<std::string> &args);
  void init() override;
  void tick() override;

private:
  const PCIMCUTBOUNDARYBRIDGEMODULE_struct mmio_addrs;
  uint32_t PCIM_INIT_TOKENS = 0;
};

#endif //__PCIM_CUTBOUNDARY_H__
