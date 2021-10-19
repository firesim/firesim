// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
#include <map>
#include <queue>
#include <random>
#include <gmp.h>
#include <sys/time.h>
#define TIME_DIV_CONST 1000000.0;
typedef uint64_t midas_time_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

class simif_t
{
  public:
    simif_t();
    virtual ~simif_t() { }
  private:
    // simulation information
    bool log;
    // random numbers
    uint64_t seed;
    std::mt19937_64 gen;
    SIMULATIONMASTER_struct * master_mmio_addrs;
    LOADMEMWIDGET_struct * loadmem_mmio_addrs;
    CLOCKBRIDGEMODULE_struct * clock_bridge_mmio_addrs;
    midas_time_t start_time, end_time;
    uint64_t start_hcycle = -1;
    uint64_t end_hcycle = 0;
    uint64_t end_tcycle = 0;

    virtual void load_mem(std::string filename);

  public:
    // Simulation APIs
    virtual void init(int argc, char** argv);
    inline bool done() { return read(this->master_mmio_addrs->DONE); }
    inline void take_steps(size_t n, bool blocking) {
      write(this->master_mmio_addrs->STEP, n);
      if (blocking) while(!done());
    }

    // Host-platform interface. See simif_f1; simif_emul for implementation examples

    // Performs platform-level initialization that for some reason or another
    // cannot be done in the constructor. (For one, currently command line args
    // are not passed to constructor).
    virtual void host_init(int argc, char** argv) = 0;
    // Does final platform-specific cleanup before destructors are called.
    virtual int host_finish() = 0;

    // Widget communication
    // 32b MMIO, issued over the simulation control bus (AXI4-lite).
    virtual void write(size_t addr, data_t data) = 0;
    virtual data_t read(size_t addr) = 0;

    // Bulk transfers / bridge streaming interfaces.
    virtual ssize_t init_stream(
      int idx,
      size_t buffer_high_addr,
      size_t buffer_low_addr,
      size_t bytes_available_addr,
      size_t bytes_consumed_addr,
      size_t done_init_addr,
      size_t flush_addr,
      size_t flush_done_addr) = 0;

    // Moves <bytes>B of data from a bridge FIFO at address <addr> (on the
    // FPGA) to a buffer specified by <data>. FIFO addresses are emitted in the
    // simulation header, and are in a distinct address space from MMIO.
    virtual ssize_t pull(size_t addr, char *data, size_t size) = 0;
    virtual void flush_tohost_stream(size_t addr) = 0;
    virtual ssize_t push(size_t addr, char *data, size_t size) = 0;

    // End host-platform interface.

    // LOADMEM functions
    void read_mem(size_t addr, mpz_t& value);
    void write_mem(size_t addr, mpz_t& value);
    void write_mem_chunk(size_t addr, mpz_t& value, size_t bytes);
    void zero_out_dram();

    uint64_t get_seed() { return seed; };

    // Returns the current target cycle of the fastest clock in the simulated system, based
    // on the number of clock tokens enqueued (will report a larger number)
    uint64_t actual_tcycle();
    // Returns the current host cycle as measured by a hardware counter
    uint64_t hcycle();
    uint64_t rand_next(uint64_t limit) { return gen() % limit; }

    void record_start_times();
    void record_end_times();
    uint64_t get_end_tcycle() { return end_tcycle; }
    void print_simulation_performance_summary();
};

class StreamHandler {
  public:
    StreamHandler(size_t buffer_high_addr, size_t buffer_low_addr, size_t bytes_available_addr, size_t bytes_consumed_addr, size_t done_init_addr, void* buffer_base, size_t buffer_phys_addr, size_t flush_addr, size_t flush_done_addr, simif_t* sim):
        addr_hi(buffer_high_addr),
        addr_lo(buffer_low_addr),
        bytes_available_addr(bytes_available_addr),
        bytes_consumed_addr(bytes_consumed_addr),
        done_init_addr(done_init_addr),
        buffer_base(buffer_base),
        buffer_phys_addr(buffer_phys_addr),
        flush_addr(flush_addr),
        flush_done_addr(flush_done_addr),
        sim(sim) {
      sim->write(addr_hi, buffer_phys_addr >> 32);
      sim->write(addr_lo, buffer_phys_addr);
      sim->write(done_init_addr, 1);
    };

    size_t bytes_available() { return sim->read(bytes_available_addr); };

    size_t pull(char* data, size_t size) {
      assert(size <= PCIM_CIRCULAR_BUFFER_SIZE);
      size_t bytes_in_buffer = sim->read(bytes_available_addr);
      if (bytes_in_buffer < size) {
        return 0;
      }

      void* src_addr = (char*)buffer_base + offset;
      size_t first_copy_bytes = ((offset + size) > PCIM_CIRCULAR_BUFFER_SIZE) ? PCIM_CIRCULAR_BUFFER_SIZE - offset : size;
      printf("dst %x, src: %x, size: %d\n", data, src_addr, size);
      std::memcpy(data, src_addr, first_copy_bytes);
      if (first_copy_bytes < size) {
        printf("Secondary memcopy dst %x, src: %x, size: %d\n", (char*) data + first_copy_bytes, buffer_base, size - first_copy_bytes);
        std::memcpy((char*) data + first_copy_bytes, buffer_base, size - first_copy_bytes);
      }
      offset = (offset + size) % PCIM_CIRCULAR_BUFFER_SIZE;
      sim->write(bytes_consumed_addr, size);
      return size;
    }

    void flush() {
      sim->write(flush_addr, 1);
      fprintf(stderr, "Driver: Flushing\n");
      printf("Driver: Flushing\n");
      // TODO: Consider if this should be made non blocking // alternate API
      auto flush_done = false;
      while (!flush_done) {
        flush_done = (sim->read(flush_done_addr) & 1);
        fprintf(stderr, "Flush in progress? %d\n", flush_done);
      }
    }

  private:
    unsigned offset = 0;
    const size_t addr_hi;
    const size_t addr_lo;
    const size_t bytes_available_addr;
    const size_t bytes_consumed_addr;
    const size_t done_init_addr;
    const void*  buffer_base;
    const size_t buffer_phys_addr;
    const size_t flush_addr;
    const size_t flush_done_addr;
    simif_t* sim;
};

#endif // __SIMIF_H
