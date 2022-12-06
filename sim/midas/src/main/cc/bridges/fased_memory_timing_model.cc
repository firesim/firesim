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
    unsigned modelno,
    const std::vector<std::string> &args,
    const std::string &stats_file_name,
    size_t mem_size)
    : FpgaModel(&simif, addr_map), widget_t(simif, &KIND), mem_size(mem_size) {

  std::string suffix = "_" + std::to_string(modelno);
  for (auto &arg : args) {
    if (arg.find("+mm_") == 0 && arg.find(suffix) != std::string::npos) {
      auto sub_arg = arg.substr(4);
      size_t delimit_idx = sub_arg.find("=");
      size_t suffix_idx = sub_arg.find(suffix);
      std::string key = sub_arg.substr(0, suffix_idx).c_str();

      // This is the only nullary plusarg supported by fased
      // All other plusargs are key-value pairs that will be written to the
      // bridge module
      if (key == std::string("useHardwareDefaultRuntimeSettings")) {
        require_all_runtime_settings = false;
      } else if (suffix_idx == std::string::npos) {
        throw std::runtime_error("[FASED] unknown nullary plusarg: " + key);
      } else {
        int value = std::stoi(sub_arg.substr(delimit_idx + 1).c_str());
        model_configuration[key] = value;
      }
    }
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
  for (auto &pair : addr_map.w_registers) {
    auto value_it = model_configuration.find(pair.first);
    if (value_it != model_configuration.end()) {
      write(pair.second, value_it->second);
    } else {
      // Iterate through substrings to exclude
      bool exclude = false;
      for (auto &substr : configuration_exclusion) {
        if (pair.first.find(substr) != std::string::npos) {
          exclude = true;
        }
      }

      if (!exclude) {
        if (require_all_runtime_settings) {
          char buf[100];
          sprintf(buf,
                  "[FASED] No value provided for configuration register: %s",
                  pair.first.c_str());
          throw std::runtime_error(buf);
        } else {
          auto init_val = read(pair.first);
          fprintf(stderr,
                  "[FASED] Using hardware default of %u for configuration "
                  "register %s\n",
                  init_val,
                  pair.first.c_str());
        }
      } else {
        fprintf(stderr,
                "[FASED] Ignoring writeable register: %s\n",
                pair.first.c_str());
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
