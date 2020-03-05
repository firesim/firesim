#ifdef AUTOCOUNTERBRIDGEMODULE_struct_guard

#include "autocounter.h"

#include <iostream>
#include <stdio.h>
#include <string.h>
#include <limits.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

autocounter_t::autocounter_t(
    simif_t *sim, std::vector<std::string> &args, AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs, AddressMap addr_map, int autocounterno) : bridge_driver_t(sim), addr_map(addr_map)
{
    this->mmio_addrs = mmio_addrs;

    this->readrate = 0;
    this->autocounter_filename = "AUTOCOUNTER";
    const char *autocounter_filename_in = NULL;
    std::string num_equals   =        std::to_string(autocounterno) + std::string("=");
    std::string readrate_arg =        std::string("+autocounter-readrate") + num_equals;
    std::string filename_arg =        std::string("+autocounter-filename") + num_equals;

    for (auto &arg: args) {
        if (arg.find(readrate_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + readrate_arg.length();
            this->readrate = atol(str);;
        }
        if (arg.find(filename_arg) == 0) {
            autocounter_filename_in = const_cast<char*>(arg.c_str()) + filename_arg.length();
            this->autocounter_filename = std::string(autocounter_filename_in);
        }
    }

    autocounter_file.open(this->autocounter_filename, std::ofstream::out);
    if(!autocounter_file.is_open()) {
      throw std::runtime_error("Could not open output file: " + this->autocounter_filename);
    }
}

autocounter_t::~autocounter_t() {
    free(this->mmio_addrs);
}

void autocounter_t::init() {
    cur_cycle = 0;
    // Decrement the readrate by one to simplify the HW a little bit
    write(addr_map.w_registers.at("readrate_low"), (readrate - 1) & ((1ULL << 32) - 1));
    write(addr_map.w_registers.at("readrate_high"), this->readrate >> 32);
    write(mmio_addrs->init_done, 1);
}

bool autocounter_t::drain_sample() {
  bool bridge_has_sample = read(addr_map.r_registers.at("countersready"));

  if (bridge_has_sample) {
    cur_cycle = read(this->mmio_addrs->cycles_low);
    cur_cycle |= ((uint64_t)read(this->mmio_addrs->cycles_high)) << 32;
    autocounter_file << "Cycle " << cur_cycle << std::endl;
    autocounter_file << "============================" << std::endl;
    for (auto pair: addr_map.r_registers) {

      std::string low_prefix = std::string("autocounter_low_");
      std::string high_prefix = std::string("autocounter_high_");

      if (pair.first.find("autocounter_low_") == 0) {
          char *str = const_cast<char*>(pair.first.c_str()) + low_prefix.length();
          std::string countername(str);
          uint64_t counter_val = ((uint64_t) (read(addr_map.r_registers.at(high_prefix + countername)))) << 32;
          counter_val |= read(pair.second);
          autocounter_file << "PerfCounter " << str << ": " << counter_val << std::endl;
      }

    }
    write(addr_map.w_registers.at("readdone"), 1);
    autocounter_file << "" << std::endl;
  }
  return bridge_has_sample;
}

void autocounter_t::tick() {
  drain_sample();
}

void autocounter_t::finish() {
 while(drain_sample());
}

#endif // AUTOCOUNTERBRIDGEMODULE_struct_guard
