#ifdef TERMINATIONBRIDGEMODULE_struct_guard

#include "termination.h"
#include <iostream>

termination_t::termination_t(
  simif_t* sim, 
  std::vector<std::string> &args,
  TERMINATIONBRIDGEMODULE_struct * mmio_addrs,
  unsigned int num_messages,
  unsigned int* isErr,
  const char* const* msgs): 
  bridge_driver_t(sim), 
  mmio_addrs(mmio_addrs), 
  num_messages(num_messages), 
  isErr(isErr),
  msgs(msgs)
{
  //tick-rate to decide sampling rate of MMIOs per number of ticks
  this->mmio_addrs = mmio_addrs;
  std::string tick_rate_arg = std::string("+tick-rate=");
  for (auto &arg: args) {
    if (arg.find(tick_rate_arg) == 0) {
      char *str = const_cast<char*>(arg.c_str()) + tick_rate_arg.length();
      uint64_t tick_period = atol(str);
      this->tick_rate = tick_period;
    }
  }
}

termination_t::~termination_t() {
  free(this->mmio_addrs);
}

void termination_t::tick() {  //reads the MMIOs tick-rate
  if(tick_counter == tick_rate) {
    if (read(this->mmio_addrs->out_status)) {
      int msg_id = read(this->mmio_addrs->out_errCode);
    	this->fail = this->isErr[msg_id];
      test_done = true;
			if(msg_id < this->num_messages) {
    	  std::cerr << "Termination Bridge detected exit on cycle " << this->cycleCount()
			  << " with message " << this->msgs[msg_id] << std::endl;
			} else {
			  std::cerr << "Unknown Termination message" << std::endl;
			}
    }
    tick_counter = 0;
  } else {
  	tick_counter +=1;
  }
}

const char* termination_t::exit_message() {
  int msg_id = read(this->mmio_addrs->out_errCode);
  return this->msgs[msg_id];
}

int termination_t::cycleCount() {
  write(this->mmio_addrs->out_counter_latch, 1);
  data_t cycle_l = read(this->mmio_addrs->out_counter_0);
  data_t cycle_h = read(this->mmio_addrs->out_counter_1);
  return (((uint64_t) cycle_h) << 32) | cycle_l;
}

#endif
