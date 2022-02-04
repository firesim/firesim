// See LICENSE for license details.

#include "simif_emul.h"
#ifdef VCS
#include "emul/vcs_main.h"
#include <fesvr/context.h>
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#endif
#include <signal.h>

uint64_t main_time = 0;
std::unique_ptr<mmio_t> master;
std::unique_ptr<mmio_t> dma;

#ifdef VCS
context_t* host;
context_t target;
bool vcs_rst = false;
bool vcs_fin = false;
#else
Vverilator_top* top = NULL;
#if VM_TRACE
VerilatedVcdC* tfp = NULL;
#endif // VM_TRACE
double sc_time_stamp() {
  return (double) main_time;
}
extern void tick();
#endif // VCS

void finish() {
#ifdef VCS
  vcs_fin = true;
  target.switch_to();
#else
#if VM_TRACE
  if (tfp) tfp->close();
  delete tfp;
#endif // VM_TRACE
#endif // VCS
}

void handle_sigterm(int sig) {
  finish();
}

simif_emul_t::~simif_emul_t() { }

void simif_emul_t::host_init(int argc, char** argv) {
  // Parse args
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string waveform = "dump.vcd";
  std::string loadmem;
  bool fastloadmem = false;
  bool dramsim = false;
  uint64_t memsize = 1L << MEM_ADDR_BITS;
  for (auto arg: args) {
    if (arg.find("+waveform=") == 0) {
      waveform = arg.c_str() + 10;
    }
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+dramsim") == 0) {
      dramsim = true;
    }
    if (arg.find("+memsize=") == 0) {
      memsize = strtoll(arg.c_str() + 9, NULL, 10);
    }
    if (arg.find("+fuzz-host-timing=") == 0) {
      maximum_host_delay = atoi(arg.c_str() + 18);
    }
  }

  ::init(memsize, dramsim);
  if (fastloadmem && !loadmem.empty()) {
    fprintf(stdout, "[fast loadmem] %s\n", loadmem.c_str());
    ::load_mems(loadmem.c_str());
  }

  signal(SIGTERM, handle_sigterm);
#ifdef VCS
  host = context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  vcs_rst = true;
  for (size_t i = 0 ; i < 10 ; i++)
    target.switch_to();
  vcs_rst = false;
#else
  Verilated::commandArgs(argc, argv); // Remember args

  top = new Vverilator_top;
#if VM_TRACE                         // If emul was invoked with --trace
  tfp = new VerilatedVcdC;
  Verilated::traceEverOn(true);      // Verilator must compute traced signals
  VL_PRINTF("Enabling waves: %s\n", waveform.c_str());
  top->trace(tfp, 99);                // Trace 99 levels of hierarchy
  tfp->open(waveform.c_str());        // Open the dump file
#endif // VM_TRACE

  top->reset = 1;
  for (size_t i = 0 ; i < 10 ; i++) ::tick();
  top->reset = 0;
#endif
}

int simif_emul_t::host_finish() {
  ::finish();
  return 0;
}

void simif_emul_t::advance_target() {
    int cycles_to_wait = rand_next(maximum_host_delay) + 1;
    for (int i = 0; i < cycles_to_wait; i++) {
#ifdef VCS
        target.switch_to();
#else
        ::tick();
#endif
    }
}

void simif_emul_t::wait_write(std::unique_ptr<mmio_t>& mmio) {
  while(!mmio->write_resp()) advance_target();
}

void simif_emul_t::wait_read(std::unique_ptr<mmio_t>& mmio, void *data) {
  while(!mmio->read_resp(data)) advance_target();
}

void simif_emul_t::write(size_t addr, data_t data) {
  size_t strb = (1 << CTRL_STRB_BITS) - 1;
  static_assert(CTRL_AXI4_SIZE == 2, "AXI4-lite control interface has unexpected size");
  master->write_req(addr, CTRL_AXI4_SIZE, 0, &data, &strb);
  wait_write(master);
}

data_t simif_emul_t::read(size_t addr) {
  data_t data;
  master->read_req(addr, CTRL_AXI4_SIZE, 0);
  wait_read(master, &data);
  return data;
}

#define MAX_LEN 255

ssize_t simif_emul_t::pull(size_t addr, char* data, size_t size) {
  ssize_t len = (size - 1) / DMA_BEAT_BYTES;

  while (len >= 0) {
      size_t part_len = len % (MAX_LEN + 1);

      dma->read_req(addr, DMA_SIZE, part_len);
      wait_read(dma, data);

      len -= (part_len + 1);
      addr += (part_len + 1) * DMA_BEAT_BYTES;
      data += (part_len + 1) * DMA_BEAT_BYTES;
  }
  return size;
}

ssize_t simif_emul_t::push(size_t addr, char *data, size_t size) {
  ssize_t len = (size - 1) / DMA_BEAT_BYTES;
  size_t remaining = size - len * DMA_BEAT_BYTES;
  size_t strb[len + 1];
  size_t *strb_ptr = &strb[0];

  for (int i = 0; i < len; i++)
      strb[i] = (1LL << DMA_BEAT_BYTES) - 1;

  if (remaining == DMA_BEAT_BYTES)
      strb[len] = strb[0];
  else
      strb[len] = (1LL << remaining) - 1;

  while (len >= 0) {
      size_t part_len = len % (MAX_LEN + 1);

      dma->write_req(addr, DMA_SIZE, part_len, data, strb_ptr);
      wait_write(dma);

      len -= (part_len + 1);
      addr += (part_len + 1) * DMA_BEAT_BYTES;
      data += (part_len + 1) * DMA_BEAT_BYTES;
      strb_ptr += (part_len + 1);
  }

  return size;
}
