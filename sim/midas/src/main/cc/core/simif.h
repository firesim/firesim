// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>

#include <memory>
#include <queue>
#include <random>
#include <sstream>

#include <map>

#include "bridges/clock.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"
#include "core/timing.h"
#include "core/widget_registry.h"

class StreamEngine;
class simulation_t;
class CPUManagedStreamIO;
class FPGAManagedStreamIO;
class TargetConfig;

/** \class simif_t
 *
 *  @brief FireSim's main simulation class.
 *
 *  Historically this god class wrapped all of the features presented by FireSim
 *  / MIDAS-derived simulators. Critically, it declares an interface for
 *  interacting with the host-FPGA, which consist of methods for implementing
 *  32b MMIO (read, write), and latency-insensitive bridge streams (push, pull).
 *  Concrete subclasses of simif_t must be written for metasimulation and each
 *  supported host plaform. See simif_f1_t for an example.
 *  simif_t also provides a few core functions that are tied to bridges and
 *  widgets that must be present in all simulators:
 *
 *  - To track simulation time, it provides methods to interact with the
 *    ClockBridge. This bridge is solely responsible for defining a schedule of
 *    clock edges to simulate, and must be instantiated in all targets. See
 *    actual_tcycle() and hcycle().  Utilities to report performance are based
 *    off these measures of time.
 *
 *  - To read and write into FPGA DRAM, the LoadMem widget provides a
 *    low-bandwidth side channel via MMIO. See read_mem, write_mem,
 *    zero_out_dram.
 */
class simif_t {
public:
  simif_t(const TargetConfig &config, const std::vector<std::string> &args);
  virtual ~simif_t();

public:
  /** Bridge / Widget MMIO methods */

  /**
   * @brief 32b MMIO write, issued over the simulation control bus (AXI4-lite).
   *
   * @param addr The address to preform the 32b read in the MMIO address space..
   */
  virtual void write(size_t addr, uint32_t data) = 0;

  /**
   * @brief 32b MMIO read, issued over the simulation control bus (AXI4-lite).
   *
   * @param addr The address to preform the 32b read in the MMIO address space..
   * @returns A uint32_t capturing the read value.
   */
  virtual uint32_t read(size_t addr) = 0;

  /**
   * Return a functor accessing CPU-managed streams.
   *
   * This method aborts if it is not reimplemented by the desired host
   * platform simif.
   */
  virtual CPUManagedStreamIO &get_cpu_managed_stream_io();

  /**
   * Return a functor accessing FPGA-managed streams.
   *
   * This method aborts if it is not reimplemented by the desired host platform
   * simif.
   */
  virtual FPGAManagedStreamIO &get_fpga_managed_stream_io();

  // End host-platform interface.

  /**
   * Provides the current target cycle of the fastest clock.
   *
   * The target cycle is based on the number of clock tokens enqueued
   * (will report a larger number).
   */
  uint64_t actual_tcycle() {
    return registry.get_widget<clockmodule_t>().tcycle();
  }

  /**
   * Provides the current host cycle.
   */
  uint64_t actual_hcycle() {
    return registry.get_widget<clockmodule_t>().hcycle();
  }

  /**
   * Return the name of the simulated target.
   */
  std::string_view get_target_name() const;

  /**
   * Return a reference to the registry which owns all widgets.
   */
  widget_registry_t &get_registry() { return registry; }

  /**
   * Runs the simulation in the context of the driver.
   *
   * The default implementation initialises the target and hands control to
   * the driver routines, suitable for actual FPGA implementations.
   */
  virtual int run(simulation_t &sim);

protected:
  /**
   * Sets up the target and blocks until it is initialized.
   */
  void target_init();

protected:
  /**
   * Target configuration.
   */
  const TargetConfig &config;

  /**
   * Saved command-line arguments.
   */
  std::vector<std::string> args;

  /**
   * Helper holding references to all bridges.
   */
  widget_registry_t registry;

  /**
   * Path to load DRAM contents from.
   */
  std::string load_mem_path;

  bool fastloadmem = false;
  // If set, will write all zeros to fpga dram before commencing simulation
  bool do_zero_out_dram = false;
};

#endif // __SIMIF_H
