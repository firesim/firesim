
#include "qsfp_cutbridge.h"
#include "core/simif.h"

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

/* #define DEBUG_QSFP_BRIDGE */

char qsfp_cutbridge_t::KIND;

qsfp_cutbridge_t::qsfp_cutbridge_t(
    simif_t &simif,
    StreamEngine &stream,
    const QSFPCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
    const int idx,
    const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), mmio_addrs(mmio_addrs) {
  std::string batch_size = std::string("+batch-size=");

  for (auto &arg : args) {
    if (arg.find(batch_size) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + batch_size.length();
      this->QSFP_INIT_TOKENS = atoi(str);
    }
  }
  this->CHANNEL = idx;
}

void qsfp_cutbridge_t::init() {
  write(mmio_addrs.init_simulator_tokens, this->QSFP_INIT_TOKENS);
  write(mmio_addrs.init_simulator_tokens_valid, 1);
  fprintf(stdout, "qsfp_control_bridge init done\n");
}

void qsfp_cutbridge_t::tick() {
#ifdef DEBUG_QSFP_BRIDGE
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
