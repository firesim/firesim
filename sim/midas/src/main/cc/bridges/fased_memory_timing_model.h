// See LICENSE for license details.

#ifndef __FASED_MEMORY_TIMING_MODEL_H
#define __FASED_MEMORY_TIMING_MODEL_H

/* This is the widget driver for FASED memory-timing models
 *
 * FASED instances are FPGA-hosted and only rely on this driver to:
 * 1) set runtime-configurable timing parameters before simulation commences
 * 2) poll instrumentation registers
 *
 */

#include <fstream>
#include <set>
#include <unordered_map>

#include "fpga_model.h"

// Bridge Driver Instantiation Template
// Casts are required for now since the emitted type can change
#define INSTANTIATE_FASED(FUNC, IDX)                                           \
  FUNC(new FASEDMemoryTimingModel(                                             \
      this,                                                                    \
      AddressMap(FASEDMEMORYTIMINGMODEL_##IDX##_R_num_registers,               \
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_##IDX##_R_addrs, \
                 (const char *const *)FASEDMEMORYTIMINGMODEL_##IDX##_R_names,  \
                 FASEDMEMORYTIMINGMODEL_##IDX##_W_num_registers,               \
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_##IDX##_W_addrs, \
                 (const char *const *)FASEDMEMORYTIMINGMODEL_##IDX##_W_names), \
      argc,                                                                    \
      argv,                                                                    \
      "memory_stats" #IDX ".csv",                                              \
      1L << FASEDMEMORYTIMINGMODEL_##IDX##_target_addr_bits,                   \
      "_" #IDX));

// MICRO HACKS.
constexpr int HISTOGRAM_SIZE = 1024;
constexpr int BIN_SIZE = 36;
constexpr int RANGE_COUNT_SIZE = 48;
constexpr uint32_t BIN_H_MASK = (1L << (BIN_SIZE - 32)) - 1;
constexpr uint32_t RANGE_H_MASK = (1L << (RANGE_COUNT_SIZE - 32)) - 1;

class AddrRangeCounter final : public FpgaModel {
public:
  AddrRangeCounter(simif_t *sim, AddressMap addr_map, std::string name)
      : FpgaModel(sim, addr_map), name(name){};

  void init();
  void profile() {}
  void finish();

  std::string name;
  uint64_t *range_bytes;
  size_t nranges;

private:
  std::string enable = name + "Ranges_enable";
  std::string dataH = name + "Ranges_dataH";
  std::string dataL = name + "Ranges_dataL";
  std::string addr = name + "Ranges_addr";
};

class Histogram final : public FpgaModel {
public:
  Histogram(simif_t *s, AddressMap addr_map, std::string name)
      : FpgaModel(s, addr_map), name(name){};

  void init();
  void profile(){};
  void finish();
  std::string name;
  uint64_t latency[HISTOGRAM_SIZE];

private:
  std::string enable = name + "Hist_enable";
  std::string dataH = name + "Hist_dataH";
  std::string dataL = name + "Hist_dataL";
  std::string addr = name + "Hist_addr";
};

class FASEDMemoryTimingModel : public FpgaModel {
public:
  FASEDMemoryTimingModel(simif_t *s,
                         AddressMap addr_map,
                         int argc,
                         char **argv,
                         std::string stats_file_name,
                         size_t mem_size,
                         std::string suffix);
  void init();
  void profile();
  void finish();

private:
  // Saves a map of register names to settings
  std::unordered_map<std::string, uint32_t> model_configuration;
  std::vector<uint32_t> profile_reg_addrs;
  std::ofstream stats_file;
  std::vector<Histogram> histograms;
  std::vector<AddrRangeCounter> rangectrs;
  std::set<std::string> configuration_exclusion{"Hist_dataL",
                                                "Hist_dataH",
                                                "Hist_addr",
                                                "Hist_enable",
                                                "hostMemOffsetLow",
                                                "hostMemOffsetHigh",
                                                "Ranges_dataL",
                                                "Ranges_dataH",
                                                "Ranges_addr",
                                                "Ranges_enable",
                                                "numRanges"};

  std::set<std::string> profile_exclusion{"Hist_dataL",
                                          "Hist_dataH",
                                          "Hist_addr",
                                          "Hist_enable",
                                          "hostMemOffsetLow",
                                          "hostMemOffsetHigh",
                                          "Ranges_dataL",
                                          "Ranges_dataH",
                                          "Ranges_addr",
                                          "Ranges_enable",
                                          "numRanges"};

  bool has_latency_histograms() { return histograms.size() > 0; };
  size_t mem_size;
  // By default, FASED requires that plus args for all timing model parameters
  // are passed in to prevent accidental misconfigurations (ex. when
  // DRAM timing parameters are passed to an LBP). When this is set, using the
  // plus arg +mm_useHardwareDefaultRuntimeSettings_<idx>, the driver will
  // instead use the hardware reset values (which map to the values emitted in
  // the runtime.conf) and print those values to the log instead.
  bool require_all_runtime_settings = true;
};

#endif // __FASED_MEMORY_TIMING_MODEL_H
