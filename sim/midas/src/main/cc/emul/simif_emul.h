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
#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"
#include "emul/mm.h"
#include "emul/mmio.h"

class mm_t;
class mmio_t;

/**
 * Simulation implementation bridging an RTL DUT to a simulation.
 */
class simif_emul_t : public simif_t {
public:
  simif_emul_t(const TargetConfig &config,
               const std::vector<std::string> &args);

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
   * Start the driver thread.
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
  void start_driver(simulation_t &sim);

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

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  /**
   * Returns a pointer to the CPU-managed AXI4 IF at the metasimulation
   * boundary.
   *
   * This is used by the harness to bridge the driver and the simulation.
   */
  mmio_t *get_cpu_managed_axi4() {
    if (cpu_managed_stream_io)
      return &cpu_managed_stream_io->cpu_managed_axi4;
    return nullptr;
  }

  /**
   * Returns a pointer to a mm_t instance written to by FPGA-managed AXI4
   * transactions during metasimulation.
   *
   * This is used by the harness to bridge the driver and the simulation.
   */
  mm_t *get_fpga_managed_axi4() {
    if (fpga_managed_stream_io)
      return &fpga_managed_stream_io->cpu_mem;
    return nullptr;
  }

  /**
   * @brief Pointers to inter-context (i.e., between VCS/verilator and driver)
   * AXI4 transaction channels
   */
  std::unique_ptr<mmio_t> master;

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
   * Return the wrapper around the CPU-managed stream AXI interface.
   */
  CPUManagedStreamIO &get_cpu_managed_stream_io() override {
    return *cpu_managed_stream_io;
  }

  /**
   * Return the wrapper around the FPGA-managed stream AXI interface.
   */
  FPGAManagedStreamIO &get_fpga_managed_stream_io() override {
    return *fpga_managed_stream_io;
  }

protected:
  // The maximum number of cycles the RTL simulator can advance before
  // switching back to the driver process. +fuzz-host-timings sets this to a
  // value > 1, introducing random delays in axi4 tranactions that MMIO and
  // bridge streams.
  int maximum_host_delay = 1;

  // random numbers
  uint64_t fuzz_seed = 0;
  std::mt19937_64 fuzz_gen;

  std::string waveform = "dump.vcd";

  uint64_t memsize;

  /**
   * Advances the simulation by a random number of ticks.
   *
   * The number of ticks is bounded by `maximum_host_delay`.
   */
  inline void advance_target() {
    unsigned nticks = fuzz_gen() % maximum_host_delay + 1;
    for (unsigned i = 0; i < nticks; ++i)
      do_tick();
  }

  void wait_read(mmio_t &mmio, void *data);
  void wait_write(mmio_t &mmio);

  // Writes directly into the host DRAM models to initialize them.
  void load_mems(const char *fname);

private:
  class CPUManagedStreamIOImpl final : public CPUManagedStreamIO {
  public:
    CPUManagedStreamIOImpl(simif_emul_t &simif, const AXI4Config &config)
        : simif(simif), cpu_managed_axi4(config) {}

    uint32_t mmio_read(size_t addr) override { return simif.read(addr); }

    size_t
    cpu_managed_axi4_write(size_t addr, const char *data, size_t size) override;

    size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) override;

    uint64_t get_beat_bytes() const override {
      return cpu_managed_axi4.get_config().beat_bytes();
    }

    simif_emul_t &simif;
    mmio_t cpu_managed_axi4;
  };

  std::unique_ptr<CPUManagedStreamIOImpl> cpu_managed_stream_io;

private:
  class FPGAManagedStreamIOImpl final : public FPGAManagedStreamIO {
  public:
    FPGAManagedStreamIOImpl(simif_emul_t &simif, const AXI4Config &config);

    uint32_t mmio_read(size_t addr) override { return simif.read(addr); }

    void mmio_write(size_t addr, uint32_t value) override {
      return simif.write(addr, value);
    }

    char *get_memory_base() override { return ((char *)cpu_mem.get_data()); }

    simif_emul_t &simif;

    /**
     * @brief A model of FPGA-addressable CPU-host memory.
     *
     * In metasimulations, FPGA-managed AXI4 transactions read and write to this
     * AXI4 memory subordinate as a proxy for writing into actual host-CPU DRAM.
     * The driver-side of FPGAManagedStreams inspect circular buffers hosted
     * here.
     */
    mm_magic_t cpu_mem;
  };

  std::unique_ptr<FPGAManagedStreamIOImpl> fpga_managed_stream_io;

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

  // Synchronisation primitives blocking the driver.  The driver thread
  // waits on the condition variable until the simulator allows it to proceed.
  std::mutex driver_mutex;
  std::condition_variable driver_cond;
  bool driver_flag;

  // Synchronisation primitives blocking the simulator.  The simulator thread
  // is the main thread, invoking the DPI tick function.  After information is
  // passed on, the simulator thread yields to the driver and waits on these
  // variables until the driver performs a tick.
  std::mutex rtlsim_mutex;
  std::condition_variable rtlsim_cond;
  bool rtlsim_flag;
};

#endif // __SIMIF_EMUL_H
