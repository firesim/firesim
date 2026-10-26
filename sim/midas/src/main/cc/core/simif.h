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

#include "core/timing.h"
#include "core/widget_registry.h"

class StreamEngine;
class simulation_t;
class CPUManagedStreamIO;
class FPGAManagedStreamIO;
class TargetConfig;

/**
 * FireSim's main simulation class.
 *
 *
 * It declares an interface for interacting with the host FPGA or simulator,
 * which consist of methods for implementing 32b MMIO (read, write), and
 * latency-insensitive bridge streams (push, pull). Concrete subclasses of
 * simif_t must be written for metasimulation and each supported host plaform.
 * See simif_f1_t for an example.
 */
class simif_t {
public:
  simif_t(const TargetConfig &config);

  virtual ~simif_t();

public:
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
   * Optionally accept a complete, padded loadmem write through a bulk
   * transport. Addresses are loadmem-widget DRAM offsets, after any bridge host
   * offset has been applied. Return false without side effects to use the MMIO
   * widget. On success, copy or consume the caller's bytes before returning;
   * the caller retains ownership. A backend may defer the device transfer until
   * a flush.
   */
  virtual bool write_dram_bulk(size_t, const void *, size_t) { return false; }

  /**
   * Make accepted bulk writes visible to subsequent device accesses, preserving
   * write order. Loadmem flushes before reads, MMIO write fallbacks, DRAM
   * clearing and file completion. A deferring backend must also flush before
   * starting/resuming target execution.
   */
  virtual void flush_dram_writes() {}

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

  /**
   * Return the name of the simulated target.
   */
  std::string_view get_target_name() const;

  /**
   * Runs the simulation in the context of the driver.
   *
   * The default implementation initialises the target and hands control to
   * the driver routines, suitable for actual FPGA implementations.
   */
  virtual int run(simulation_t &sim);

protected:
  /**
   * Target configuration.
   */
  const TargetConfig &config;
};

#endif // __SIMIF_H
