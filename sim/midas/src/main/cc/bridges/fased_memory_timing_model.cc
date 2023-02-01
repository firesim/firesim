// See LICENSE for license details.

#include <algorithm>
#include <exception>
#include <iostream>
#include <stdio.h>

#include "fased_memory_timing_model.h"

void Histogram::init() {
  // Read out the initial values
  write(enable, 1);
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    write(addr, i);
    latency[i] = read64(dataH, dataL, BIN_H_MASK);
  }
  // Disable readout enable; otherwise histogram updates will be gated
  write(enable, 0);
}

void Histogram::finish() {
  // Read out the initial values
  write(enable, 1);
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    write(addr, i);
    latency[i] = read64(dataH, dataL, BIN_H_MASK) - latency[i];
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

char FASEDMemoryTimingModel::KIND;

FASEDMemoryTimingModel::FASEDMemoryTimingModel(
    simif_t &simif,
    const AddressMap &addr_map,
    const std::vector<std::string> &args,
    const std::string &stats_file_name,
    size_t mem_size,
    const std::string &suffix)
    : FpgaModel(&simif, addr_map), widget_t(simif, &KIND), mem_size(mem_size) {

  for (auto &arg : args) {
    // Find arguments: +mm_{name}{index}={value}
    if (arg.find("+mm_") != 0 || arg.find(suffix) == std::string::npos) {
      continue;
    }

    auto sub_arg = arg.substr(4);
    size_t delimit_idx = sub_arg.find("=");
    size_t suffix_idx = sub_arg.find(suffix);
    std::string key = sub_arg.substr(0, suffix_idx).c_str();

    // This is the only nullary plusarg supported by fased
    if (key == std::string("useHardwareDefaultRuntimeSettings")) {
      useHardwareDefaults = false;
      continue;
    }

    // All other plusargs are key-value pairs that will be written to the
    // bridge module
    if (suffix_idx == std::string::npos) {
      throw std::runtime_error("[FASED] unknown nullary plusarg: " + key);
    }
    if (!addr_map.w_reg_exists(key)) {
      throw std::runtime_error("[FASED] unknown register: " + key);
    }
    int value = std::stoi(sub_arg.substr(delimit_idx + 1).c_str());
    user_configuration[key] = value;
  }

  stats_file.open(stats_file_name, std::ofstream::out);
  if (!stats_file.is_open()) {
    throw std::runtime_error("Could not open output file: " + stats_file_name);
  }

  for (const auto &pair : addr_map.r_registers) {
    // Only profile readable registers
    if (!addr_map.w_reg_exists((pair.first))) {
      // Iterate through substrings to exclude
      bool exclude = false;
      for (auto &substr : profile_exclusion) {
        if (pair.first.find(substr) != std::string::npos) {
          exclude = true;
        }
      }
      if (!exclude) {
        profile_reg_addrs.push_back(pair.second);
        stats_file << pair.first << ",";
      }
    }
  }
  stats_file << std::endl;

  if (addr_map.w_reg_exists("hostReadLatencyHist_enable")) {
    histograms.emplace_back(&simif, addr_map, "hostReadLatency");
    histograms.emplace_back(&simif, addr_map, "hostWriteLatency");
    histograms.emplace_back(&simif, addr_map, "targetReadLatency");
    histograms.emplace_back(&simif, addr_map, "targetWriteLatency");
    histograms.emplace_back(&simif, addr_map, "ingressReadLatency");
    histograms.emplace_back(&simif, addr_map, "ingressWriteLatency");
    histograms.emplace_back(&simif, addr_map, "totalReadLatency");
    histograms.emplace_back(&simif, addr_map, "totalWriteLatency");
  }

  if (addr_map.w_reg_exists("readRanges_enable")) {
    rangectrs.emplace_back(&simif, addr_map, "read");
    rangectrs.emplace_back(&simif, addr_map, "write");
  }
}

void FASEDMemoryTimingModel::profile() {
  for (auto addr : profile_reg_addrs) {
    stats_file << read(addr) << ",";
  }
  stats_file << std::endl;
}

void FASEDMemoryTimingModel::init() {
  for (auto &[key, addr] : addr_map.w_registers) {
    // If the user provided a configuration option, use it and
    // overwrite whatever the hardware register was initialized to.
    auto user_it = user_configuration.find(key);
    if (user_it != user_configuration.end()) {
      write(addr, user_it->second);
      continue;
    }

    // If the registers is excluded, emit a warning only when some, but not
    // all, registers have been specified through arguments.
    bool exclude = false;
    for (auto &substr : configuration_exclusion) {
      if (key.find(substr) != std::string::npos) {
        exclude = true;
      }
    }
    if (exclude && !user_configuration.empty()) {
      fprintf(stderr, "[FASED] Ignoring writeable register: %s\n", key.c_str());
      continue;
    }

    // If the register is not excluded and some other registers were
    // given initial values, emit a diagnostic message. If hardware
    // defaults are allowed to be mixed in with the configuration, produce
    // the initial value. Otherwise, error out.
    if (!user_configuration.empty()) {
      if (useHardwareDefaults) {
        auto init_val = read(key);
        fprintf(stderr,
                "[FASED] Using hardware default of %u for configuration "
                "register %s\n",
                init_val,
                key.c_str());
      } else {
        char buf[100];
        sprintf(buf,
                "[FASED] No value provided for configuration register: %s",
                key.c_str());
        throw std::runtime_error(buf);
      }
    }
  }

  for (auto &hist : histograms) {
    hist.init();
  }
  for (auto &rctr : rangectrs) {
    rctr.init();
  }
}

void FASEDMemoryTimingModel::finish() {
  for (auto &hist : histograms) {
    hist.finish();
  }
  for (auto &rctr : rangectrs) {
    rctr.finish();
  }

  std::ofstream histogram_file;
  histogram_file.open("latency_histogram.csv", std::ofstream::out);
  if (!histogram_file.is_open()) {
    throw std::runtime_error("Could not open histogram output file");
  }

  // Header
  for (auto &hist : histograms) {
    histogram_file << hist.name << ",";
  }
  histogram_file << std::endl;
  // Data
  for (size_t i = 0; i < HISTOGRAM_SIZE; i++) {
    for (auto &hist : histograms) {
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
    for (auto &rctr : rangectrs) {
      rangectr_file << rctr.name << ",";
    }
    rangectr_file << std::endl;

    for (size_t i = 0; i < nranges; i++) {
      rangectr_file << std::hex << (i * mem_size / nranges) << ",";
      for (auto &rctr : rangectrs) {
        rangectr_file << std::dec << rctr.range_bytes[i] << ",";
      }
      rangectr_file << std::endl;
    }
    rangectr_file.close();
  }

  stats_file.close();
}
