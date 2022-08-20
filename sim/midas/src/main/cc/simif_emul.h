// See LICENSE for license details.

#ifndef __SIMIF_EMUL_H
#define __SIMIF_EMUL_H

#include <vector>

#include "bridges/cpu_managed_stream.h"
#include "emul/mmio.h"
#include "mm.h"
#include "mm_dramsim2.h"
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
  inline static mmio_t *master = new mmio_t(CTRL_BEAT_BYTES);
  inline static mmio_t *dma = new mmio_t(DMA_BEAT_BYTES);
  /**
   * @brief Host DRAM models shared across the RTL simulator and driver
   * contexts.
   *
   * The driver only needs access to these to implement a faster form of
   * loadmem, which instead of using mmio (~10 cycles per MMIO transaction),
   * writes directly into the backing memory (0 cycles). See simif_emul_t::load_mems.
   */
  inline static mm_t* slave[MEM_NUM_CHANNELS] = {nullptr};

private:
  // The maximum number of cycles the RTL simulator can advance before
  // switching back to the driver process. +fuzz-host-timings sets this to a
  // value > 1, introducing random delays in MMIO (read, write) and DMA (push,
  // pull) requests
  int maximum_host_delay = 1;
  void advance_target();
  void wait_read(mmio_t *mmio, void *data);
  void wait_write(mmio_t *mmio);

  size_t pcis_write(size_t addr, char *data, size_t size);
  size_t pcis_read(size_t addr, char *data, size_t size);

  // Writes directly into the host DRAM models to initialize them.
  void load_mems(const char *fname);

  std::vector<StreamToCPU> to_host_streams;
  std::vector<StreamFromCPU> from_host_streams;
};

#endif // __SIMIF_EMUL_H
