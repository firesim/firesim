#include <iostream>
#include <algorithm>
#include <exception>
#include <stdio.h>

#include "fpga_memory_model.h"

FpgaMemoryModel::FpgaMemoryModel(
    simif_t* sim, AddressMap addr_map, int argc, char** argv, std::string stats_file_name)
  : FpgaModel(sim, addr_map) {
  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if(arg.find("+mm_") == 0) {
      auto sub_arg = std::string(arg.c_str() + 4);
      size_t delimit_idx = sub_arg.find_first_of("=");
      std::string key = sub_arg.substr(0, delimit_idx).c_str();
      int value = std::stoi(sub_arg.substr(delimit_idx+1).c_str());
      model_configuration[key] = value;
    }
  }

  stats_file.open(stats_file_name, std::ofstream::out);
  if(!stats_file.is_open()) {
    throw std::runtime_error("Could not open output file: " + stats_file_name);
  }

  for (auto pair: addr_map.r_registers) {
    stats_file << pair.first << ",";
  }
  stats_file << std::endl;

}

void FpgaMemoryModel::profile() {
  for (auto pair: addr_map.r_registers) {
    stats_file << read(pair.first) << ",";
  }
  stats_file << std::endl;
}

void FpgaMemoryModel::init() {
  for (auto &pair: addr_map.w_registers) {
    auto value_it = model_configuration.find(pair.first);
    if (value_it != model_configuration.end()) {
      write(pair.second, value_it->second);
    } else {
      char buf[100];
      sprintf(buf, "No value provided for configuration register: %s", pair.first.c_str());
      throw std::runtime_error(buf);
    }
  }

}

void FpgaMemoryModel::finish() {
  stats_file.close();
}
