// See LICENSE for license details.

#ifndef __FPGA_MEMORY_MODEL_H
#define __FPGA_MEMORY_MODEL_H

#include <unordered_map>
#include <set>
#include <fstream>

#include "fpga_model.h"
// Driver for the midas memory model

// MICRO HACKS.
constexpr int HISTOGRAM_SIZE = 1024;
constexpr int BIN_SIZE = 36;
constexpr data_t BIN_H_MASK = (1L << (BIN_SIZE - 32)) - 1;
constexpr data_t NUM_RANGE_MASK = (1L << 4) - 1;
constexpr data_t RANGE_BYTES_MASK = (1L << 16) - 1;
constexpr uint64_t MEM_SIZE (1L << 35);

class AddrRangeCounter: public FpgaModel {
  public:
    AddrRangeCounter(simif_t *sim, AddressMap addr_map, std::string name):
      FpgaModel(sim, addr_map), name(name) {};
    ~AddrRangeCounter(void) { /*delete [] range_bytes;*/ }

    void init();
    void profile() {}
    void finish();

    std::string name;
    uint64_t *range_bytes;
    size_t nranges;

  private:
    std::string enable = name + "Ranges_enable";
    std::string dataH  = name + "Ranges_dataH";
    std::string dataL  = name + "Ranges_dataL";
    std::string addr   = name + "Ranges_addr";
};

class Histogram: public FpgaModel {
public:
  Histogram(simif_t* s, AddressMap addr_map,  std::string name): FpgaModel(s, addr_map), name(name) {};
  void init();
  void profile() {};
  void finish();
  std::string name;
  uint64_t latency[HISTOGRAM_SIZE];

private:
  std::string enable = name + "Hist_enable";
  std::string dataH = name  + "Hist_dataH";
  std::string dataL = name  + "Hist_dataL";
  std::string addr = name   + "Hist_addr";
};

class FpgaMemoryModel: public FpgaModel
{
public:
  FpgaMemoryModel(simif_t* s, AddressMap addr_map, int argc, char** argv,
                  std::string stats_file_name);
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
  std::set<std::string> configuration_exclusion {
    "Hist_dataL",
    "Hist_dataH",
    "Hist_addr",
    "Hist_enable",
    "Ranges_dataL",
    "Ranges_dataH",
    "Ranges_addr",
    "Ranges_enable"
  };

  std::set<std::string> profile_exclusion {
    "Hist_dataL",
    "Hist_dataH",
    "Hist_addr",
    "Hist_enable",
    "Ranges_dataL",
    "Ranges_dataH",
    "Ranges_addr",
    "Ranges_enable"
  };

  bool has_latency_histograms() { return histograms.size() > 0; };
};

#endif // __FPGA_MEMORY_MODEL_H
