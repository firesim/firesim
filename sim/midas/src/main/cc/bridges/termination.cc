#include "termination.h"

#include <cassert>

#include <iostream>

char termination_t::KIND;

termination_t::termination_t(simif_t &sim,
                             const std::vector<std::string> &args,
                             const TERMINATIONBRIDGEMODULE_struct &mmio_addrs,
                             unsigned int num_messages,
                             unsigned int *is_err,
                             const char *const *msgs)
    : bridge_driver_t(sim, &KIND), mmio_addrs(mmio_addrs),
      num_messages(num_messages), is_err(is_err), msgs(msgs) {
  // tick-rate to decide sampling rate of MMIOs per number of ticks
  std::string tick_rate_arg = std::string("+termination-bridge-tick-rate=");
  for (auto &arg : args) {
    if (arg.find(tick_rate_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + tick_rate_arg.length();
      uint64_t tick_period = atol(str);
      this->tick_rate = tick_period;
    }
  }
}

termination_t::~termination_t() = default;

void termination_t::tick() { // reads the MMIOs at tick-rate
  if (tick_counter == tick_rate) {
    if (read(mmio_addrs.out_status)) {
      int msg_id = read(mmio_addrs.out_terminationCode);
      assert(msg_id < this->num_messages);
      this->fail = this->is_err[msg_id];
      test_done = true;
      std::cerr << "Termination Bridge detected exit on cycle "
                << this->cycle_count() << " with message:" << std::endl
                << this->msgs[msg_id] << std::endl;
    }
    tick_counter = 0;
  } else {
    tick_counter += 1;
  }
}

const char *termination_t::exit_message() {
  int msg_id = read(mmio_addrs.out_terminationCode);
  return this->msgs[msg_id];
}

int termination_t::cycle_count() {
  write(mmio_addrs.out_counter_latch, 1);
  uint32_t cycle_l = read(mmio_addrs.out_counter_0);
  uint32_t cycle_h = read(mmio_addrs.out_counter_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}
