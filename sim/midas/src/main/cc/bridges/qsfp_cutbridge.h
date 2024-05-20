#ifndef __QSFP_CUTBRIDGE_H__
#define __QSFP_CUTBRIDGE_H__

#include <cstdint>
#include <vector>

#include "core/bridge_driver.h"
#include "core/stream_engine.h"

struct QSFPCUTBOUNDARYBRIDGEMODULE_struct {
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

class qsfp_cutbridge_t final : public bridge_driver_t {
public:
  static char KIND;

  qsfp_cutbridge_t(simif_t &simif,
                   StreamEngine &stream,
                   const QSFPCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
                   int idx,
                   const std::vector<std::string> &args);
  void init() override;
  void tick() override;

private:
  const QSFPCUTBOUNDARYBRIDGEMODULE_struct mmio_addrs;
  uint64_t QSFP_INIT_TOKENS = 0;
  int CHANNEL;
};

#endif // __QSFP_CUTBRIDGE_H__
