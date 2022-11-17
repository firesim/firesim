// See LICENSE for license details.

#ifndef __SIMIF_EMUL_H
#define __SIMIF_EMUL_H

#include <vector>

#include "bridges/cpu_managed_stream.h"
#include "emul/mm.h"
#include "emul/mmio.h"
#include "simif.h"

// simif_emul_t is a concrete simif_t implementation for Software RTL simulators
// The basis for MIDAS-level simulation
class simif_emul_t : public virtual simif_t {
public:
  simif_emul_t();
  virtual ~simif_emul_t();
  virtual void host_init(int argc, char **argv);
  virtual int host_finish();

  virtual void write(size_t addr, uint32_t data);
  virtual uint32_t read(size_t addr);

  virtual size_t pull(unsigned int stream_idx,
                      void *dest,
                      size_t num_bytes,
                      size_t threshold_bytes);
  virtual size_t push(unsigned int stream_idx,
                      void *src,
                      size_t num_bytes,
                      size_t threshold_bytes);
  /**
   * @brief Pointers to inter-context (i.e., between VCS/verilator and driver)
   * AXI4 transaction channels
   *
   * These have external linkage to enable VCS to easily access them.
   */
  static mmio_t *master;
  static mmio_t *cpu_managed_axi4;
  /**
   * @brief Host DRAM models shared across the RTL simulator and driver
   * contexts.
   *
   * The driver only needs access to these to implement a faster form of
   * loadmem, which instead of using mmio (~10 cycles per MMIO transaction),
   * writes directly into the backing memory (0 cycles). See
   * simif_emul_t::load_mems.
   */
  static mm_t *slave[MEM_NUM_CHANNELS];
  /**
   * @brief A model of FPGA-addressable CPU-host memory.
   *
   * In metasimulations, FPGA-managed AXI4 transactions read and write to this
   * AXI4 memory subordinate as a proxy for writing into actual host-CPU DRAM.
   * The driver-side of FPGAManagedStreams inspect circular buffers hosted here.
   */
  static mm_t *cpu_mem;

private:
  // The maximum number of cycles the RTL simulator can advance before
  // switching back to the driver process. +fuzz-host-timings sets this to a
  // value > 1, introducing random delays in axi4 tranactions that MMIO and
  // bridge streams.
  int maximum_host_delay = 1;
  void advance_target();
  void wait_read(mmio_t *mmio, void *data);
  void wait_write(mmio_t *mmio);

  size_t cpu_managed_axi4_write(size_t addr, char *data, size_t size);
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size);

  // Writes directly into the host DRAM models to initialize them.
  void load_mems(const char *fname);

  std::vector<StreamToCPU> to_host_streams;
  std::vector<StreamFromCPU> from_host_streams;
};

#endif // __SIMIF_EMUL_H
