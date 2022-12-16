// See LICENSE for license details.

#ifndef MIDASEXAMPLES_REGFILETEST_H
#define MIDASEXAMPLES_REGFILETEST_H

#include "TestHarness.h"

#include <stdio.h>
#include <stdlib.h>

#include <map>
#include <utility>

constexpr size_t reg_n_reads = 2;
constexpr size_t reg_n_writes = 2;

const std::string_view reg_w_addr_ios[reg_n_writes] = {"io_writes_0_addr",
                                                       "io_writes_1_addr"};
const std::string_view reg_w_data_ios[reg_n_writes] = {"io_writes_0_data",
                                                       "io_writes_1_data"};
const std::string_view reg_w_en_ios[reg_n_writes] = {"io_writes_0_en",
                                                     "io_writes_1_en"};

const std::string_view reg_r_addr_ios[reg_n_reads] = {"io_reads_0_addr",
                                                      "io_reads_1_addr"};
const std::string_view reg_r_data_ios[reg_n_reads] = {"io_reads_0_data",
                                                      "io_reads_1_data"};

class RegfileTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    for (size_t cycle = 0; cycle < 100; cycle++) {
      do_iteration(cycle);
    }
  }

private:
  size_t mem_depth = 10;

  void do_iteration(size_t cycle_num) {
    for (size_t w_idx = 0; w_idx < reg_n_writes; w_idx++) {
      if (rand() % 2) {
        uint32_t rand_data = rand();
        uint32_t rand_addr = rand() % mem_depth;
        while (history.count(rand_addr) &&
               history[rand_addr].second == cycle_num) {
          // already written this cycle => would be undefined write collision
          rand_addr = rand() % mem_depth;
        }
        history[rand_addr] = std::make_pair(rand_data, cycle_num);
        poke(reg_w_addr_ios[w_idx], rand_addr);
        poke(reg_w_data_ios[w_idx], rand_data);
        poke(reg_w_en_ios[w_idx], 1);
      } else {
        poke(reg_w_en_ios[w_idx], 0);
      }
    }
    for (size_t r_idx = 0; r_idx < reg_n_reads; r_idx++) {
      uint32_t rand_addr = rand() % mem_depth;
      prev_reads[r_idx] = rand_addr;
      poke(reg_r_addr_ios[r_idx], rand_addr);
    }
    step(1);
    for (size_t r_idx = 0; r_idx < reg_n_reads; r_idx++) {
      uint32_t prev_addr = prev_reads[r_idx];
      if (history.count(prev_addr)) {
        expect(reg_r_data_ios[r_idx], history[prev_addr].first);
      }
    }
  }

private:
  std::map<uint32_t, std::pair<uint32_t, size_t>> history;
  std::map<size_t, uint32_t> prev_reads;
};

#endif // MIDASEXAMPLES_REGFILETEST_H
