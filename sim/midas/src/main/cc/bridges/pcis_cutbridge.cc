#include "pcis_cutbridge.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdlib.h>
#include <unistd.h>

#include <fcntl.h>
#include <inttypes.h>
#include <iostream>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sys/mman.h>

char pcis_cutbridge_t::KIND;

/* #define LOG_TO_UART */
/* #define PRINT_INPUT_TOKENS */
/* #define PRINT_OUTPUT_TOKENS */

pcis_cutbridge_t::pcis_cutbridge_t(
    simif_t &sim,
    StreamEngine &stream,
    const PCISCUTBOUNDARYBRIDGEMODULE_struct &mmio_addrs,
    int dma_no,
    const std::vector<std::string> &args,
    int stream_to_cpu_idx,
    int to_host_dma_transactions,
    int stream_from_cpu_idx,
    int from_host_dma_transactions)
    : streaming_bridge_driver_t(sim, stream, &KIND), mmio_addrs(mmio_addrs),
      stream_to_cpu_idx(stream_to_cpu_idx),
      stream_from_cpu_idx(stream_from_cpu_idx) {
  std::string cutbridge_idx_arg =
      std::string("+cutbridgeidx") + std::to_string(dma_no) + std::string("=");
  std::string batchsize_arg = std::string("+batch-size=");

  for (auto &arg : args) {
    if (arg.find(cutbridge_idx_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + cutbridge_idx_arg.length();
      this->bridge_idx = atoi(str);
    }
    if (arg.find(batchsize_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + batchsize_arg.length();
      this->BATCHSIZE = atoi(str);
    }
  }

  printf("bridge_idx %d\n", this->bridge_idx);
  printf("dma_no %d\n", dma_no);

  assert(this->BATCHSIZE > 0);

  this->tick_tracker = 0;
  this->currentround = 0;

  this->TO_HOST_PER_TRANSACTION_BYTES =
      BIGTOKEN_BYTES * to_host_dma_transactions * this->BATCHSIZE;
  this->FROM_HOST_PER_TRANSACTION_BYTES =
      BIGTOKEN_BYTES * from_host_dma_transactions * this->BATCHSIZE;

  printf("BATCHSIZE: %d, TO_HOST_PER_TRANSACTION_BYTES: %d, "
         "FROM_HOST_PER_TRANSACTION_BYTES: %d\n",
         this->BATCHSIZE,
         this->TO_HOST_PER_TRANSACTION_BYTES,
         this->FROM_HOST_PER_TRANSACTION_BYTES);

  char name[257];
  int shmemfd;

  for (int ii = 0; ii < 2; ii++) {
    sprintf(name, "/pipe_h2t%03d_%d", this->bridge_idx, ii);
    printf("opening/creating shmem region\n%s\n", name);

    shmemfd = shm_open(name, O_RDWR | O_CREAT, S_IRWXU);
    if (shmemfd == -1) {
      printf("failed to open shared mem file descriptor\n");
      exit(1);
    }
    if (ftruncate(shmemfd, FROM_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES) ==
        -1) {
      printf("failed on ftruncate\n");
      exit(1);
    }
    pcie_write_buf[ii] =
        (char *)mmap(nullptr,
                     FROM_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES,
                     PROT_READ | PROT_WRITE,
                     MAP_SHARED,
                     shmemfd,
                     0);
    uint64_t pcie_write_buf_addr = (uint64_t)pcie_write_buf[ii];
    printf("pcis_cutbridge_t[%d] pcie_write_buf %" PRIx64 "\n",
           this->bridge_idx,
           pcie_write_buf_addr);

    sprintf(name, "/pipe_t2h%03d_%d", this->bridge_idx, ii);
    printf("opening/creating shmem region\n%s\n", name);

    shmemfd = shm_open(name, O_RDWR | O_CREAT, S_IRWXU);
    if (shmemfd == -1) {
      printf("failed to open shared mem file descriptor\n");
      exit(1);
    }
    if (ftruncate(shmemfd, TO_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES) == -1) {
      printf("failed on ftruncate\n");
      exit(1);
    }
    pcie_read_buf[ii] =
        (char *)mmap(nullptr,
                     TO_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES,
                     PROT_READ | PROT_WRITE,
                     MAP_SHARED,
                     shmemfd,
                     0);
    uint64_t pcie_read_buf_addr = (uint64_t)pcie_read_buf[ii];
    printf("pcis_cutbridge_t[%d] pcie_read_buf %" PRIx64 "\n",
           this->bridge_idx,
           pcie_read_buf_addr);
  }

  printf("pcis_cutbridge_t::pcis_cutbridge_t done\n");
}

pcis_cutbridge_t::~pcis_cutbridge_t() {
  for (int ii = 0; ii < 2; ii++) {
    munmap(pcie_read_buf[ii], TO_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES);
    munmap(pcie_write_buf[ii], FROM_HOST_PER_TRANSACTION_BYTES + EXTRA_BYTES);
  }
}

void pcis_cutbridge_t::init() {
  write(mmio_addrs.init_simulator_tokens, 1);
  write(mmio_addrs.init_simulator_tokens_valid, 1);

  printf("pcis_cutbridge_t::init[%d] this->pull\n", this->bridge_idx);

  currentround = 0;

  auto token_bytes_received = this->pull(stream_to_cpu_idx,
                                         pcie_read_buf[currentround],
                                         TO_HOST_PER_TRANSACTION_BYTES,
                                         0);

  if (token_bytes_received != 0) {
    printf("CutBoundaryBridge should have zero tokens when starting");
    assert(false);
  }

  printf("pcis_cutbridge_t::init[%d] token_bytes_received %lu\n",
         this->bridge_idx,
         token_bytes_received);

  printf("pcis_cutbridge_t::init[%d] this->push\n", this->bridge_idx);

  // NOTE: We need to use 0xff for initialization because of the reset signal
  // should be high
  for (int ii = 0; ii < FROM_HOST_PER_TRANSACTION_BYTES; ii++) {
    pcie_write_buf[currentround][ii] = 0xff;
  }

  auto token_bytes_pushed = this->push(stream_from_cpu_idx,
                                       pcie_write_buf[currentround],
                                       FROM_HOST_PER_TRANSACTION_BYTES,
                                       0);
  printf("pcis_cutbridge_t::init[%d] token_bytes_pushed %lu\n",
         this->bridge_idx,
         token_bytes_pushed);

  printf("pcis_cutbridge_t::init done\n");
}

void pcis_cutbridge_t::tick() {
#ifdef LOG_TO_UART
  firesplit_printf("pcis_cutbridge_t::tick()[%d] round %d\n",
                   this->bridge_idx,
                   currentround);
#endif

  while (true) {
    // Read the tokens from pcie & place it in pcie_read_buf
    auto token_bytes_pulled = 0;

    token_bytes_pulled = this->pull(stream_to_cpu_idx,
                                    pcie_read_buf[currentround],
                                    TO_HOST_PER_TRANSACTION_BYTES,
                                    TO_HOST_PER_TRANSACTION_BYTES);

#ifdef LOG_TO_UART
    firesplit_printf("tick %" PRIu64 " [%d] pulled %d bytes round %d\n",
                     tick_tracker,
                     this->bridge_idx,
                     token_bytes_pulled,
                     currentround);
#endif

    if (token_bytes_pulled == 0) {
      return;
    }

    if (token_bytes_pulled != TO_HOST_PER_TRANSACTION_BYTES) {
      printf("ERR MISMATCH! on reading tokens out. actually read %d bytes, "
             "wanted %d bytes.\n",
             token_bytes_pulled,
             TO_HOST_PER_TRANSACTION_BYTES);
      exit(1);
    }

    pcie_read_buf[currentround][TO_HOST_PER_TRANSACTION_BYTES] = 1;

#ifdef LOG_TO_UART
    firesplit_printf(
        "tick %" PRIu64 " [%d] pcie_read_buf[%d][%d]: %d\n",
        tick_tracker,
        this->bridge_idx,
        currentround,
        TO_HOST_PER_TRANSACTION_BYTES,
        pcie_read_buf[currentround][TO_HOST_PER_TRANSACTION_BYTES]);
    firesplit_printf(
        "tick %" PRIu64 " [%d] pcie_write_buf[%d][%d]: %d\n",
        tick_tracker,
        this->bridge_idx,
        currentround,
        TO_HOST_PER_TRANSACTION_BYTES,
        pcie_write_buf[currentround][TO_HOST_PER_TRANSACTION_BYTES]);
#endif
#ifdef PRINT_OUTPUT_TOKENS
    for (int ii = 0; ii < (TO_HOST_PER_TRANSACTION_BYTES / BIGTOKEN_BYTES);
         ii++) {
      uint8_t *out_token_uint8 =
          (uint8_t *)(pcie_read_buf[currentround] + ii * BIGTOKEN_BYTES);

      firesplit_printf("TOKEN_OUT tick %" PRIu64 " 0x", tick_tracker);
      for (int jj = BIGTOKEN_BYTES - 1; jj >= 0; jj--) {
        firesplit_printf("%02" PRIx8, out_token_uint8[jj]);
      }
      firesplit_printf("\n");
    }
#endif

    volatile uint8_t *polladdr = (uint8_t *)(pcie_write_buf[currentround] +
                                             FROM_HOST_PER_TRANSACTION_BYTES);
    while (*polladdr == 0) {
      ;
    }

#ifdef LOG_TO_UART
    firesplit_printf("tick %" PRIu64 " round %d polladdr != 0\n",
                     tick_tracker,
                     currentround);
#endif
#ifdef PRINT_INPUT_TOKENS
    for (int ii = 0; ii < (FROM_HOST_PER_TRANSACTION_BYTES / BIGTOKEN_BYTES);
         ii++) {
      uint8_t *in_token_uint8 =
          (uint8_t *)(pcie_write_buf[currentround] + ii * BIGTOKEN_BYTES);

      firesplit_printf("TOKEN_IN tick %" PRIu64 " 0x", tick_tracker);
      for (int jj = BIGTOKEN_BYTES - 1; jj >= 0; jj--) {
        firesplit_printf("%02" PRIx8, in_token_uint8[jj]);
      }
      firesplit_printf("\n");
    }
#endif

    auto token_bytes_pushed = 0;
    token_bytes_pushed = this->push(stream_from_cpu_idx,
                                    pcie_write_buf[currentround],
                                    FROM_HOST_PER_TRANSACTION_BYTES,
                                    FROM_HOST_PER_TRANSACTION_BYTES);

    pcie_write_buf[currentround][FROM_HOST_PER_TRANSACTION_BYTES] = 0;

    if (token_bytes_pushed != FROM_HOST_PER_TRANSACTION_BYTES) {
      printf("ERR MISMATCH!! on writing tokens in. actually wrote in %d bytes,"
             "wanted %d bytes.\n",
             token_bytes_pushed,
             FROM_HOST_PER_TRANSACTION_BYTES);
      exit(1);
    }

#ifdef LOG_TO_UART
    firesplit_printf("tick %" PRIu64 " pushed %d bytes round %d\n",
                     tick_tracker,
                     token_bytes_pushed,
                     currentround);
#endif

    currentround = (currentround + 1) % 2;
    tick_tracker++;
  }
}
