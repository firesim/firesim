// See LICENSE for license details.

#include "simif.h"

#include <stdlib.h>
#include <stdio.h>

#include <utility>
#include <map>

constexpr size_t multireg_n_copies = 4;
constexpr size_t multireg_n_reads = 2;
constexpr size_t multireg_n_writes = 2;

const size_t multireg_w_addr_ios[multireg_n_copies][multireg_n_writes] =
  { { io_accesses_0_writes_0_addr, io_accesses_0_writes_1_addr },
    { io_accesses_1_writes_0_addr, io_accesses_1_writes_1_addr },
    { io_accesses_2_writes_0_addr, io_accesses_2_writes_1_addr },
    { io_accesses_3_writes_0_addr, io_accesses_3_writes_1_addr } };

const size_t multireg_w_data_ios[multireg_n_copies][multireg_n_writes] =
  { { io_accesses_0_writes_0_data, io_accesses_0_writes_1_data },
    { io_accesses_1_writes_0_data, io_accesses_1_writes_1_data },
    { io_accesses_2_writes_0_data, io_accesses_2_writes_1_data },
    { io_accesses_3_writes_0_data, io_accesses_3_writes_1_data } };

const size_t multireg_w_en_ios[multireg_n_copies][multireg_n_writes] =
  { { io_accesses_0_writes_0_en, io_accesses_0_writes_1_en },
    { io_accesses_1_writes_0_en, io_accesses_1_writes_1_en },
    { io_accesses_2_writes_0_en, io_accesses_2_writes_1_en },
    { io_accesses_3_writes_0_en, io_accesses_3_writes_1_en } };

const size_t multireg_r_addr_ios[multireg_n_copies][multireg_n_reads] =
  { { io_accesses_0_reads_0_addr, io_accesses_0_reads_1_addr },
    { io_accesses_1_reads_0_addr, io_accesses_1_reads_1_addr },
    { io_accesses_2_reads_0_addr, io_accesses_2_reads_1_addr },
    { io_accesses_3_reads_0_addr, io_accesses_3_reads_1_addr } };

const size_t multireg_r_data_ios[multireg_n_copies][multireg_n_reads] =
  { { io_accesses_0_reads_0_data, io_accesses_0_reads_1_data },
    { io_accesses_1_reads_0_data, io_accesses_1_reads_1_data },
    { io_accesses_2_reads_0_data, io_accesses_2_reads_1_data },
    { io_accesses_3_reads_0_data, io_accesses_3_reads_1_data } };

class MultiRegfile_t: virtual simif_t
{
public:

  size_t mem_depth = 10;

  MultiRegfile_t(int argc, char** argv) {}

  void run() {
    target_reset();
    for (int cycle = 0; cycle < 100; cycle++) {
      do_iteration();
    }
  }

  void do_iteration(void) {
    for (size_t i = 0; i < multireg_n_copies; i++) {
      for (size_t w_idx = 0; w_idx < multireg_n_writes; w_idx++) {
        if (rand() % 2) {
          data_t rand_addr = rand() % mem_depth;
          data_t rand_data = rand();
          history[std::make_pair(i, rand_addr)] = rand_data;
          poke(multireg_w_addr_ios[i][w_idx], rand_addr);
          poke(multireg_w_data_ios[i][w_idx], rand_data);
          poke(multireg_w_en_ios[i][w_idx], 1);
        } else {
          poke(multireg_w_en_ios[i][w_idx], 0);
        }
      }
      for (size_t r_idx = 0; r_idx < multireg_n_reads; r_idx++) {
        data_t rand_addr = rand() % mem_depth;
        prev_reads[std::make_pair(i, r_idx)] = rand_addr;
        poke(multireg_r_addr_ios[i][r_idx], rand_addr);
      }
    }
    step(1);
    //peek(multireg_r_data_ios[0][0]);
    for (size_t i = 0; i < multireg_n_copies; i++) {
      for (size_t r_idx = 0; r_idx < multireg_n_reads; r_idx++) {
        data_t prev_addr = prev_reads[std::make_pair(i, r_idx)];
        auto mem_slot = std::make_pair(i, prev_addr);
        if (history.count(std::make_pair(i, prev_addr))) {
          expect(multireg_r_data_ios[i][r_idx], history[mem_slot]);
        }
      }
    }
  }

private:

  std::map<std::pair<size_t, data_t>, data_t> history;
  std::map<std::pair<size_t, size_t>, data_t> prev_reads;  
};
