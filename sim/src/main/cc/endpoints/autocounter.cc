#ifdef AUTOCOUNTERWIDGET_struct_guard

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
    simif_t *sim, std::vector<std::string> &args, AUTOCOUNTERWIDGET_struct * mmio_addrs) : endpoint_t(sim)
{
    this->mmio_addrs = mmio_addrs;

    this->readrate = 0;
    this->autocounter_filename = "AUTOCOUNTER";
    const char *autocounter_filename = NULL;
    std::string num_equals = std::to_string(coreno) + std::string("=");
    std::string readrate_arg =        std::string("+autocounter-read-rate") + num_equals;
    std::string filename_arg =        std::string("+autocounter-filename") + num_equals;

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
      throw std::runtime_error("Could not open output file: " + autocounter_filename);
    }
}

autocounter_t::~autocounter_t() {
    free(this->mmio_addrs);
}

void autocounter_t::init() {
    cur_cycle = 0;
    readrate_count = 0;
}


void autocounter_t::tick() {
  //read(this->mmio_addrs->in_ready);
  cur_cycle = read(this->mmio_addrs->cycle_low);
  cur_cycle |= ((uint64_t)read(this->mmio_addrs->cycle_high)) << 32;
  if ((cur_cycle / readrate) > readrate_count) {
    autocounter_file << "Cycle " << cur_cycle << endl;
    autocounter_file << "============================" << endl;
    for (auto pair: addr_map.r_registers) {
      // Print just read-only registers
      if (!addr_map.w_reg_exists((pair.first))) {
        autocounter_file << "PerfCounter " << pair.first << ": " pair.second<< endl;
      }
    }
    readrate_count++;
  }
}

#endif // AUTOCOUNTERWIDGET_struct_guard
