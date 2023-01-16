// See LICENSE for license details.

#ifndef MIDASEXAMPLES_MULTIREGFILETEST_H
#define MIDASEXAMPLES_MULTIREGFILETEST_H

#include "TestHarness.h"

#include <stdio.h>
#include <stdlib.h>

#include <map>
#include <utility>

constexpr size_t multireg_n_copies = 5;
constexpr size_t multireg_n_reads = 2;
constexpr size_t multireg_n_writes = 2;

const std::string_view
    multireg_w_addr_ios[multireg_n_copies][multireg_n_writes] = {
        {"io_accesses_0_writes_0_addr", "io_accesses_0_writes_1_addr"},
        {"io_accesses_1_writes_0_addr", "io_accesses_1_writes_1_addr"},
        {"io_accesses_2_writes_0_addr", "io_accesses_2_writes_1_addr"},
        {"io_accesses_3_writes_0_addr", "io_accesses_3_writes_1_addr"},
        {"io_accesses_4_writes_0_addr", "io_accesses_4_writes_1_addr"}};

const std::string_view
    multireg_w_data_ios[multireg_n_copies][multireg_n_writes] = {
        {"io_accesses_0_writes_0_data", "io_accesses_0_writes_1_data"},
        {"io_accesses_1_writes_0_data", "io_accesses_1_writes_1_data"},
        {"io_accesses_2_writes_0_data", "io_accesses_2_writes_1_data"},
        {"io_accesses_3_writes_0_data", "io_accesses_3_writes_1_data"},
        {"io_accesses_4_writes_0_data", "io_accesses_4_writes_1_data"}};

const std::string_view multireg_w_en_ios[multireg_n_copies][multireg_n_writes] =
    {{"io_accesses_0_writes_0_en", "io_accesses_0_writes_1_en"},
     {"io_accesses_1_writes_0_en", "io_accesses_1_writes_1_en"},
     {"io_accesses_2_writes_0_en", "io_accesses_2_writes_1_en"},
     {"io_accesses_3_writes_0_en", "io_accesses_3_writes_1_en"},
     {"io_accesses_4_writes_0_en", "io_accesses_4_writes_1_en"}};

const std::string_view
    multireg_r_addr_ios[multireg_n_copies][multireg_n_reads] = {
        {"io_accesses_0_reads_0_addr", "io_accesses_0_reads_1_addr"},
        {"io_accesses_1_reads_0_addr", "io_accesses_1_reads_1_addr"},
        {"io_accesses_2_reads_0_addr", "io_accesses_2_reads_1_addr"},
        {"io_accesses_3_reads_0_addr", "io_accesses_3_reads_1_addr"},
        {"io_accesses_4_reads_0_addr", "io_accesses_4_reads_1_addr"}};

const std::string_view
    multireg_r_data_ios[multireg_n_copies][multireg_n_reads] = {
        {"io_accesses_0_reads_0_data", "io_accesses_0_reads_1_data"},
        {"io_accesses_1_reads_0_data", "io_accesses_1_reads_1_data"},
        {"io_accesses_2_reads_0_data", "io_accesses_2_reads_1_data"},
        {"io_accesses_3_reads_0_data", "io_accesses_3_reads_1_data"},
        {"io_accesses_4_reads_0_data", "io_accesses_4_reads_1_data"}};

class MultiRegfileTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    for (size_t cycle = 0; cycle < 100; cycle++) {
      do_iteration(cycle);
    }
  }

protected:
  size_t mem_depth = 21;

  bool write_first = true;

  void do_iteration(size_t cycle_num) {
    for (size_t i = 0; i < multireg_n_copies; i++) {
      for (size_t w_idx = 0; w_idx < multireg_n_writes; w_idx++) {
        if (rand() % 2) {
          uint32_t rand_data = rand();
          uint32_t rand_addr = rand() % mem_depth;
          auto mem_slot = std::make_pair(i, rand_addr);
          while (history.count(mem_slot) &&
                 history[mem_slot].second == cycle_num) {
            // already written this cycle => would be undefined write collision
            mem_slot.second = rand() % mem_depth;
          }
          history[mem_slot] = std::make_pair(rand_data, cycle_num);
          poke(multireg_w_addr_ios[i][w_idx], mem_slot.second);
          poke(multireg_w_data_ios[i][w_idx], rand_data);
          poke(multireg_w_en_ios[i][w_idx], 1);
        } else {
          poke(multireg_w_en_ios[i][w_idx], 0);
        }
      }
      for (size_t r_idx = 0; r_idx < multireg_n_reads; r_idx++) {
        uint32_t rand_addr = rand() % mem_depth;
        prev_reads[std::make_pair(i, r_idx)] = rand_addr;
        poke(multireg_r_addr_ios[i][r_idx], rand_addr);
      }
    }
    step(1);
    for (size_t i = 0; i < multireg_n_copies; i++) {
      for (size_t r_idx = 0; r_idx < multireg_n_reads; r_idx++) {
        uint32_t prev_addr = prev_reads[std::make_pair(i, r_idx)];
        auto mem_slot = std::make_pair(i, prev_addr);
        if (history.count(mem_slot)) {
          auto w_op = history[mem_slot];
          if (w_op.second != cycle_num || write_first)
            expect(multireg_r_data_ios[i][r_idx], history[mem_slot].first);
        }
      }
    }
  }

  std::map<std::pair<size_t, uint32_t>, std::pair<uint32_t, size_t>> history;
  std::map<std::pair<size_t, size_t>, uint32_t> prev_reads;
};

#endif // MIDASEXAMPLES_MULTIREGFILETEST_H
