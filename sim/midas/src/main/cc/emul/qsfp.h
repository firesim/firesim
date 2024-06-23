#ifndef __QSFP_H
#define __QSFP_H

#include "core/config.h"

#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <errno.h>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <queue>
#include <semaphore.h>
#include <stdio.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <vector>

/**
 *  @brief Staging container for QSFP aurora transactions
 *
 *  Aurora interface transactions bound for the RTL-simulator and back are
 * queued up in this data structure as they wait to be driven into and out of
 * the verilator/VCS design.
 *
 *  Used for outward and inward QSFP data movement (see simif_t::to_qsfp,
 *  simit_t::from_qsfp).
 */
class qsfp_t {
public:
  qsfp_t(const FPGATopQSFPConfig &conf);
  ~qsfp_t() = default;

  uint64_t rx_bits_by_idx(int idx);
  bool rx_valid();
  bool tx_ready();
  bool channel_up();

  void tick(bool reset,
            bool tx_valid,
            std::vector<uint64_t> &tx_bits,
            bool rx_ready);

  void setup_shmem(char *owned_name, char *other_name);

  uint64_t SHMEM_EXTRABYTES = 24;
  uint64_t SHMEM_NUMBYTES = 0;
  uint64_t SHMEM_BITSBY64 = 0;
  uint64_t SHMEM_NAME_SIZE = 120;

  struct qsfp_data_t {
    std::vector<uint64_t> bits;
    qsfp_data_t(const std::vector<uint64_t> &bits_) {
      for (unsigned int bit : bits_) {
        bits.push_back(bit);
      }
    }

    int len() { return (int)bits.size(); }

    uint64_t bits_by_64(int idx) { return bits[idx]; }
  };

private:
  const FPGATopQSFPConfig conf;
  uint8_t *ownedbuf;
  uint8_t *otherbuf;

  sem_t *ownedsem;
  sem_t *othersem;

  std::queue<qsfp_data_t> tx_queue;
  std::queue<qsfp_data_t> rx_queue;

  std::vector<uint64_t> rx_bits_saved;
  bool rx_valid_saved = false;
};

#endif // __QSFP_H
