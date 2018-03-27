// See LICENSE for license details.

#ifndef __FPGA_MEMORY_MODEL_H
#define __FPGA_MEMORY_MODEL_H

#include <unordered_map>
#include <set>
#include <fstream>

#include "fpga_model.h"

// Driver for the midas memory model

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
  std::set<std::string> configuration_exclusion {
    "Hist_dataL",
    "Hist_dataH",
    "Hist_addr",
    "Hist_enable"
  };

  std::set<std::string> profile_exclusion {
    "Hist_dataL",
    "Hist_dataH",
    "Hist_addr",
    "Hist_enable"
  };

};

#endif // __FPGA_MEMORY_MODEL_H
