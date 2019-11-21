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
    simif_t *sim, std::vector<std::string> &args, AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs, AddressMap addr_map) : bridge_driver_t(sim), addr_map(addr_map)
{
    this->mmio_addrs = mmio_addrs;

    this->readrate = 0;
    this->autocounter_filename = "AUTOCOUNTER";
    const char *autocounter_filename = NULL;
    //std::string num_equals = std::to_string(coreno) + std::string("=");
    std::string readrate_arg =        std::string("+autocounter-read-rate=");
    std::string filename_arg =        std::string("+autocounter-filename=");

    for (auto &arg: args) {
        if (arg.find(readrate_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + readrate_arg.length();
            this->readrate = atol(str);;
        }
        if (arg.find(filename_arg) == 0) {
            autocounter_filename = const_cast<char*>(arg.c_str()) + filename_arg.length();
            this->autocounter_filename = std::string(autocounter_filename);
        }
    }

    autocounter_file.open(autocounter_filename, std::ofstream::out);
    if(!autocounter_file.is_open()) {
      throw std::runtime_error("Could not open autocounter output file\n");
      //throw std::runtime_error("Could not open output file: " + autocounter_filename);
    }
}

autocounter_t::~autocounter_t() {
    free(this->mmio_addrs);
}

void autocounter_t::init() {
    cur_cycle = 0;
    cur_cycle_since_trigger = 0;
    readrate_count = 0;
}


void autocounter_t::tick() {
  //read(this->mmio_addrs->in_ready);
  cur_cycle = read(this->mmio_addrs->cycles_low);
  cur_cycle |= ((uint64_t)read(this->mmio_addrs->cycles_high)) << 32;
  cur_cycle_since_trigger = read(this->mmio_addrs->cycles_since_trigger_low);
  cur_cycle_since_trigger |= ((uint64_t)read(this->mmio_addrs->cycles_since_trigger_high)) << 32;
  if ((cur_cycle_since_trigger / readrate) > readrate_count) {
    autocounter_file << "Cycle " << cur_cycle << std::endl;
    autocounter_file << "============================" << std::endl;
    for (auto pair: addr_map.r_registers) {

      std::string low_prefix = std::string("autocounter_low_");
      std::string high_prefix = std::string("autocounter_high_");

      // Print just read-only registers
      //if (!addr_map.w_reg_exists((pair.first))) {
        if (pair.first.find("autocounter_low_") == 0) {
          char *str = const_cast<char*>(pair.first.c_str()) + low_prefix.length();
          std::string countername(str);
          uint64_t counter_val = ((uint64_t) (read(addr_map.r_registers.at(high_prefix + countername)))) << 32;
          counter_val |= read(pair.second);
          autocounter_file << "PerfCounter " << str << ": " << counter_val << std::endl;
        }
      //}

    }
    readrate_count++;
    autocounter_file << "" << std::endl;
  }
}

#endif // AUTOCOUNTERBRIDGEMODULE_struct_guard
