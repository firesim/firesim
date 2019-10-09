// See LICENSE for license details.

#include <iostream>
#include <algorithm>
#include <exception>
#include <stdio.h>

#include "fased_memory_timing_model.h"

void Histogram::init() {
  // Read out the initial values
  write(enable, 1);
  for ( size_t i  = 0; i < HISTOGRAM_SIZE; i++) {
    write(addr, i);
    latency[i] = read64(dataH, dataL,  BIN_H_MASK);
  }
  // Disable readout enable; otherwise histogram updates will be gated
  write(enable, 0);
}

void Histogram::finish() {
    // Read out the initial values
    write(enable, 1);
    for ( size_t i  = 0; i < HISTOGRAM_SIZE; i++) {
      write(addr, i);
      latency[i] = read64(dataH, dataL,  BIN_H_MASK) - latency[i];
    }
    // Disable readout enable; otherwise histogram updates will be gated
    write(enable, 0);
}

void AddrRangeCounter::init() {
  nranges = read("numRanges");
  range_bytes = new uint64_t[nranges];

  write(enable, 1);
  for (size_t i = 0; i < nranges; i++) {
    write(addr, i);
    range_bytes[i] = read64(dataH, dataL, RANGE_H_MASK);
  }
  write(enable, 0);
}

void AddrRangeCounter::finish() {
  write(enable, 1);
  for (size_t i = 0; i < nranges; i++) {
    write(addr, i);
    range_bytes[i] = read64(dataH, dataL, RANGE_H_MASK);
  }
  write(enable, 0);
}

FASEDMemoryTimingModel::FASEDMemoryTimingModel(
    simif_t* sim, AddressMap addr_map, int argc,char** argv,
    std::string stats_file_name, size_t mem_size, uint64_t mem_host_offset)
  : FpgaModel(sim, addr_map), mem_size(mem_size), mem_host_offset(mem_host_offset) {

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
    // Only profile readable registers
    if (!addr_map.w_reg_exists((pair.first))) {
      // Iterate through substrings to exclude
      bool exclude = false;
      for (auto &substr: profile_exclusion) {
        if (pair.first.find(substr) != std::string::npos) { exclude = true; }
      }
      if (!exclude) {
        profile_reg_addrs.push_back(pair.second);
        stats_file << pair.first << ",";
      }
    }
  }
  stats_file << std::endl;

  if (addr_map.w_reg_exists("hostReadLatencyHist_enable")) {
    histograms.push_back(Histogram(sim, addr_map, "hostReadLatency"));
    histograms.push_back(Histogram(sim, addr_map, "hostWriteLatency"));
    histograms.push_back(Histogram(sim, addr_map, "targetReadLatency"));
    histograms.push_back(Histogram(sim, addr_map, "targetWriteLatency"));
    histograms.push_back(Histogram(sim, addr_map, "ingressReadLatency"));
    histograms.push_back(Histogram(sim, addr_map, "ingressWriteLatency"));
    histograms.push_back(Histogram(sim, addr_map, "totalReadLatency"));
    histograms.push_back(Histogram(sim, addr_map, "totalWriteLatency"));
  }

  if (addr_map.w_reg_exists("readRanges_enable")) {
    rangectrs.push_back(AddrRangeCounter(sim, addr_map, "read"));
    rangectrs.push_back(AddrRangeCounter(sim, addr_map, "write"));
  }
}

void FASEDMemoryTimingModel::profile() {
  for (auto addr: profile_reg_addrs) {
    stats_file << read(addr) << ",";
  }
  stats_file << std::endl;
}

void FASEDMemoryTimingModel::init() {
  for (auto &pair: addr_map.w_registers) {
    auto value_it = model_configuration.find(pair.first);
    if (value_it != model_configuration.end()) {
      write(pair.second, value_it->second);
    }
    else if (pair.first.find("hostMemOffsetLow") != std::string::npos) {
      write(pair.second, mem_host_offset & ((1ULL << 32) - 1));
    }
    else if (pair.first.find("hostMemOffsetHigh") != std::string::npos) {
      write(pair.second, mem_host_offset >> 32);
    }
    else {
      // Iterate through substrings to exclude
      bool exclude = false;
      for (auto &substr: configuration_exclusion) {
        if (pair.first.find(substr) != std::string::npos) { exclude = true; }
      }

      if (!exclude) {
        char buf[100];
        sprintf(buf, "No value provided for configuration register: %s", pair.first.c_str());
        throw std::runtime_error(buf);
      } else {
        fprintf(stderr, "Ignoring writeable register: %s\n", pair.first.c_str());
      }
    }
  }
  for (auto &hist: histograms) { hist.init(); }
  for (auto &rctr: rangectrs)  { rctr.init(); }
}

void FASEDMemoryTimingModel::finish() {
  for (auto &hist: histograms) { hist.finish(); }
  for (auto &rctr: rangectrs)  { rctr.finish(); }

  std::ofstream histogram_file;
  histogram_file.open("latency_histogram.csv", std::ofstream::out);
  if(!histogram_file.is_open()) {
    throw std::runtime_error("Could not open histogram output file");
  }

  // Header
  for (auto &hist: histograms) {
    histogram_file << hist.name << ",";
  }
  histogram_file << std::endl;
    // Data
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    for (auto &hist: histograms) {
      histogram_file << hist.latency[i] << ",";
    }
    histogram_file << std::endl;
  }
  histogram_file.close();

  if (!rangectrs.empty()) {
    size_t nranges = rangectrs[0].nranges;
    std::ofstream rangectr_file;

    rangectr_file.open("range_counters.csv", std::ofstream::out);
    if (!rangectr_file.is_open()) {
      throw std::runtime_error("Could not open range counter file");
    }

    rangectr_file << "Address,";
    for (auto &rctr: rangectrs) {
      rangectr_file << rctr.name << ",";
    }
    rangectr_file << std::endl;

    for (size_t i = 0; i < nranges; i++) {
      rangectr_file << std::hex << (i * mem_size / nranges) << ",";
      for (auto &rctr: rangectrs) {
        rangectr_file << std::dec << rctr.range_bytes[i] << ",";
      }
      rangectr_file << std::endl;
    }
    rangectr_file.close();
  }
  
  stats_file.close();
}
