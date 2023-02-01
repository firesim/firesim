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

#include "bridges/fpga_model.h"
#include "core/widget.h"

#include <fstream>
#include <set>
#include <unordered_map>

struct FASEDMEMORYTIMINGMODEL_struct {};

// MICRO HACKS.
constexpr int HISTOGRAM_SIZE = 1024;
constexpr int BIN_SIZE = 36;
constexpr int RANGE_COUNT_SIZE = 48;
constexpr uint32_t BIN_H_MASK = (1L << (BIN_SIZE - 32)) - 1;
constexpr uint32_t RANGE_H_MASK = (1L << (RANGE_COUNT_SIZE - 32)) - 1;

class AddrRangeCounter final : public FpgaModel {
public:
  AddrRangeCounter(simif_t *sim,
                   const AddressMap &addr_map,
                   const std::string &name)
      : FpgaModel(sim, addr_map), name(name) {}

  void init() override;
  void profile() override {}
  void finish() override;

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
  Histogram(simif_t *s, const AddressMap &addr_map, const std::string &name)
      : FpgaModel(s, addr_map), name(name) {}

  void init() override;
  void profile() override {}
  void finish() override;
  std::string name;
  uint64_t latency[HISTOGRAM_SIZE];

private:
  std::string enable = name + "Hist_enable";
  std::string dataH = name + "Hist_dataH";
  std::string dataL = name + "Hist_dataL";
  std::string addr = name + "Hist_addr";
};

class FASEDMemoryTimingModel : public FpgaModel, public widget_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  FASEDMemoryTimingModel(simif_t &simif,
                         const AddressMap &addr_map,
                         const std::vector<std::string> &args,
                         const std::string &stats_file_name,
                         size_t mem_size,
                         const std::string &suffix);
  void init() override;
  void profile() override;
  void finish() override;

private:
  /**
   * User-provided configuration options.
   */
  std::unordered_map<std::string, uint32_t> user_configuration;

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

  bool has_latency_histograms() { return !histograms.empty(); };
  size_t mem_size;
  // By default, FASED requires that plus args for all timing model parameters
  // are passed in to prevent accidental misconfigurations (ex. when
  // DRAM timing parameters are passed to an LBP). When this is set, using the
  // plus arg +mm_useHardwareDefaultRuntimeSettings_<idx>, the driver will
  // instead use the hardware reset values (which map to the values emitted in
  // the runtime.conf) and print those values to the log instead.
  bool useHardwareDefaults = false;
};

#endif // __FASED_MEMORY_TIMING_MODEL_H
