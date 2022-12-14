// See LICENSE for license details.

#ifndef __SIMIF_EMUL_H
#define __SIMIF_EMUL_H

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "bridges/cpu_managed_stream.h"
#include "emul/mm.h"
#include "emul/mmio.h"
#include "simif.h"

/**
 * Simulation implementation bridging an RTL DUT to a simulation.
 */
class simif_emul_t : public simif_t {
public:
  simif_emul_t(const std::vector<std::string> &args);

  virtual ~simif_emul_t();

  /**
   * End the simulation.
   *
   * This function joins the simulation thread. Before calling it, the
   * simulation should finish either by returning from its loop or by having
   * called finish with some other error condition.
   */
  int end();

  /**
   * Simulation thread implementation.
   *
   * This thread is synchronized by the main thread driven by Verilator/VCS
   * which simulates the RTL and invokes the tick function through DPI. At
   * any point in time, only one of the two is allowed to run and the
   * pthread synchronization primitives switch between the two contexts.
   *
   * When the thread starts, it is put to sleep.
   *
   * The simulation thread is woken by the target thread in the tick function,
   * after it posts data received from the RTL. The simulation thread then
   * runs for one cycle, handling the AXI transactions, before switching control
   * back to the target thread that reads outputs into the RTL.
   */
  void thread_main();

  /**
   * Transfers control to the simulator on a tick.
   *
   * Helper to be called solely from the target thread.
   */
  bool to_sim();

  /**
   * Transfers control to the target and lets the simulator wait for a tick.
   *
   * To be called solely from the target.
   */
  void do_tick();

  void host_mmio_init() override;

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  size_t pull(unsigned int stream_idx,
              void *dest,
              size_t num_bytes,
              size_t threshold_bytes) override;
  size_t push(unsigned int stream_idx,
              void *src,
              size_t num_bytes,
              size_t threshold_bytes) override;

  void pull_flush(unsigned int stream_no) override;
  void push_flush(unsigned int stream_no) override;

  /**
   * @brief Pointers to inter-context (i.e., between VCS/verilator and driver)
   * AXI4 transaction channels
   */
  std::unique_ptr<mmio_t> master;
  std::unique_ptr<mmio_t> cpu_managed_axi4;

  /**
   * @brief Host DRAM models shared across the RTL simulator and driver
   * contexts.
   *
   * The driver only needs access to these to implement a faster form of
   * loadmem, which instead of using mmio (~10 cycles per MMIO transaction),
   * writes directly into the backing memory (0 cycles). See
   * simif_emul_t::load_mems.
   */
  std::vector<std::unique_ptr<mm_t>> slave;

  /**
   * @brief A model of FPGA-addressable CPU-host memory.
   *
   * In metasimulations, FPGA-managed AXI4 transactions read and write to this
   * AXI4 memory subordinate as a proxy for writing into actual host-CPU DRAM.
   * The driver-side of FPGAManagedStreams inspect circular buffers hosted here.
   */
  std::unique_ptr<mm_t> cpu_mem;

protected:
  // The maximum number of cycles the RTL simulator can advance before
  // switching back to the driver process. +fuzz-host-timings sets this to a
  // value > 1, introducing random delays in axi4 tranactions that MMIO and
  // bridge streams.
  int maximum_host_delay = 1;

  std::string waveform = "dump.vcd";

  uint64_t memsize = 1L << MEM_ADDR_BITS;

  /**
   * Advances the simulation by a random number of ticks.
   *
   * The number of ticks is bounded by `maximum_host_delay`.
   */
  inline void advance_target() {
    unsigned nticks = rand_next(maximum_host_delay) + 1;
    for (unsigned i = 0; i < nticks; ++i)
      do_tick();
  }

  void wait_read(mmio_t &mmio, void *data);
  void wait_write(mmio_t &mmio);

  size_t cpu_managed_axi4_write(size_t addr, char *data, size_t size);
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size);

  // Writes directly into the host DRAM models to initialize them.
  void load_mems(const char *fname);

  std::vector<std::unique_ptr<FPGAToCPUStreamDriver>> fpga_to_cpu_streams;
  std::vector<std::unique_ptr<CPUToFPGAStreamDriver>> cpu_to_fpga_streams;

private:
  /**
   * The flag is set when the simulation thread returns.
   *
   * If finished is set, the simulation thread can be joined into the target
   * thread to gracefully finalize the simulation.
   */
  std::atomic<bool> finished;

  /**
   * Exit code returned by the simulation, if it finished.
   */
  std::atomic<int> exit_code;

  /**
   * Identifier of the simulation thread.
   *
   * The main thread is the target thread.
   */
  std::thread thread;

  // Synchronisation primitives blocking the simulator.
  std::mutex sim_mutex;
  std::condition_variable sim_cond;
  bool sim_flag;

  // Synchronisation primitives blocking the target.
  std::mutex target_mutex;
  std::condition_variable target_cond;
  bool target_flag;
};

#endif // __SIMIF_EMUL_H
