#ifndef __PCIS_CUTBOUNDARY_H__
#define __PCIS_CUTBOUNDARY_H__

#include <cstdint>
#include <vector>

#include "core/bridge_driver.h"
#include "core/stream_engine.h"

#define BIGTOKEN_BITS 512
#define BIGTOKEN_BYTES (BIGTOKEN_BITS / 8)
#define EXTRA_BYTES 1

#define firesplit_printf(...)                                                  \
  fprintf(stdout, __VA_ARGS__);                                                \
  fflush(stdout);

struct PCISCUTBOUNDARYBRIDGEMODULE_struct {
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

class pcis_cutbridge_t final : public streaming_bridge_driver_t {
public:
  static char KIND;

  pcis_cutbridge_t(simif_t &sim,
                   StreamEngine &stream,
                   const PCISCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
                   int dma_no,
                   const std::vector<std::string> &args,
                   int stream_to_cpu_idx,
                   int to_host_dma_transactions,
                   int stream_from_cpu_idx,
                   int from_host_dma_transactions);

  ~pcis_cutbridge_t() override;

  void init() override;
  void tick() override;
  void finish() override {}

private:
  const PCISCUTBOUNDARYBRIDGEMODULE_struct mmio_addrs;
  uint64_t HOST_LATENCY_INJECTION_CYCLES = 0;

  char *pcie_write_buf[2];
  char *pcie_read_buf[2];
  int currentround = 0;

  const int stream_to_cpu_idx;
  const int stream_from_cpu_idx;

  int bridge_idx;
  uint64_t tick_tracker;
  int TO_HOST_PER_TRANSACTION_BYTES = 0;
  int FROM_HOST_PER_TRANSACTION_BYTES = 0;
  int BATCHSIZE = 0;
};

#endif //__PCIS_CUTBOUNDARY_H__
