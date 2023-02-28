// See LICENSE for license details.

#ifndef __FASED_MEMORY_TIMING_MODEL_H
#define __FASED_MEMORY_TIMING_MODEL_H

#include "core/address_map.h"
#include "core/bridge_driver.h"

#include <fstream>
#include <set>
#include <unordered_map>

/**
 * Base class for (handwritten) FPGA-hosted models
 *
 * These models have two important methods:
 *
 * 1) init: Which sets their runtime configuration. Ex. The latency of
 * latency pipe
 *
 * 2) profile: Which gives a default means to read all readable registers in
 * the model, including programmable registers and instrumentation.
 *
 */
class FpgaModel {
private:
  simif_t *sim;

public:
  FpgaModel(simif_t *s, const AddressMap &addr_map)
      : sim(s), addr_map(addr_map) {}
  virtual ~FpgaModel() = default;

  virtual void init() = 0;
  virtual void profile() = 0;
  virtual void finish() = 0;

protected:
  const AddressMap &addr_map;

  void write(size_t addr, uint32_t data) { sim->write(addr, data); }

  uint32_t read(size_t addr) { return sim->read(addr); }

  void write(const std::string &reg, uint32_t data) {
    sim->write(addr_map.w_addr(reg), data);
  }

  uint32_t read(const std::string &reg) {
    return sim->read(addr_map.r_addr(reg));
  }

  uint64_t read64(const std::string &msw,
                  const std::string &lsw,
                  uint32_t upper_word_mask) {
    uint64_t data = ((uint64_t)(read(msw) & upper_word_mask)) << 32;
    return data | read(lsw);
  }
};

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

struct FASEDMEMORYTIMINGMODEL_struct {};

/**
 * This is the bridge driver for FASED memory-timing models
 *
 * FASED instances are FPGA-hosted and only rely on this driver to:
 * 1) set runtime-configurable timing parameters before simulation commences
 * 2) poll instrumentation registers
 */
class FASEDMemoryTimingModel : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  FASEDMemoryTimingModel(simif_t &simif,
                         AddressMap &&addr_map,
                         unsigned modelno,
                         const std::vector<std::string> &args,
                         const std::string &stats_file_name,
                         size_t mem_size);
  void init() override;
  void finish() override;
  void profile();

  const AddressMap &get_addr_map() const { return addr_map; }

private:
  const AddressMap addr_map;

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
