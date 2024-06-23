#include "pcim_cutbridge.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdlib.h>
#include <unistd.h>

#include <fcntl.h>
#include <inttypes.h>
#include <iostream>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sys/mman.h>

char pcim_cutbridge_t::KIND;

pcim_cutbridge_t::pcim_cutbridge_t(
    simif_t &sim,
    StreamEngine &stream,
    const PCIMCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
    int idx,
    const std::vector<std::string> &args)
    : streaming_bridge_driver_t(sim, stream, &KIND), mmio_addrs(mmio_addrs) {
  std::string batch_size = std::string("+batch-size=");

  for (auto &arg : args) {
    if (arg.find(batch_size) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + batch_size.length();
      this->PCIM_INIT_TOKENS = atoi(str);
    }
  }
}

void pcim_cutbridge_t::init() {
  write(mmio_addrs.init_simulator_tokens, this->PCIM_INIT_TOKENS);
  write(mmio_addrs.init_simulator_tokens_valid, 1);
  fprintf(stdout, "pcim_cutbridge_t::init done\n");
}

void pcim_cutbridge_t::tick() {
#ifdef DEBUG_PCIM_BRIDGE
  uint64_t input_tokens = read(mmio_addrs.input_tokens);
  uint64_t output_tokens = read(mmio_addrs.output_tokens);
  uint64_t tokenOutQueue_io_count = read(mmio_addrs.tokenOutQueue_io_count);
  uint64_t tokenInQueue_io_count = read(mmio_addrs.tokenInQueue_io_count);
  uint64_t cutOutQueue_io_count = read(mmio_addrs.cutOutQueue_io_count);
  uint64_t cutInQueue_io_count = read(mmio_addrs.cutInQueue_io_count);
  uint64_t cur_init_tokens = read(mmio_addrs.cur_init_tokens);
  uint64_t comb_init_tokens = read(mmio_addrs.comb_init_tokens);
  uint64_t to_host_fire_count = read(mmio_addrs.to_host_fire_count);
  uint64_t from_host_fire_count = read(mmio_addrs.from_host_fire_count);
  uint64_t assert_to_host_eq = read(mmio_addrs.assert_to_host_eq);
  uint64_t assert_from_host_eq = read(mmio_addrs.assert_from_host_eq);
  uint64_t garbage_rx_cnt = read(mmio_addrs.garbage_rx_cnt);

  fprintf(stdout,
          "%d it: %d ot: %d tknOQ: %d tknIQ: %d cOQ: %d cIQ: %d thFire: %d "
          "fhFire: %d thAssert: %d fhAssert: %d init: %d comb: %d grgb: %d\n",
          CHANNEL,
          input_tokens,
          output_tokens,
          tokenOutQueue_io_count,
          tokenInQueue_io_count,
          cutOutQueue_io_count,
          cutInQueue_io_count,
          to_host_fire_count,
          from_host_fire_count,
          assert_to_host_eq,
          assert_from_host_eq,
          cur_init_tokens,
          comb_init_tokens,
          garbage_rx_cnt);
#endif
}
