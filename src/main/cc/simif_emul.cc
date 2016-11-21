#include "simif_emul.h"
#ifdef VCS
#include <DirectC.h>
#include "context.h"
#include "vcs_main.h"
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#endif
#include <signal.h>
#include <memory>

static const size_t MEM_WIDTH = MEM_DATA_BITS / 8;
static const size_t MMIO_WIDTH = CHANNEL_DATA_BITS / 8;
static uint64_t main_time = 0;
static std::unique_ptr<mmio_t> master;
static std::unique_ptr<mm_t> slave;

#if PLATFORM == zynq
#define PLATFORM_TYPE VZynqShim
#else
// TODO: error...
#endif

#ifdef VCS
static context_t* host;
static context_t target;
static bool is_reset = false;
static bool vcs_fin = false;
static bool vcs_err = false;
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);
#else
static PLATFORM_TYPE* top = NULL;
#if VM_TRACE
static VerilatedVcdC* tfp = NULL;
#endif
double sc_time_stamp() {
  return (double) main_time;
}
#endif

#if PLATFORM == zynq
#include "tick_zynq.cc"
#else
// TODO: error...
#endif // PLATFORM

void finish() {
#ifdef VCS
  vcs_fin = true;
  target.switch_to();
#else
#if VM_TRACE
  if (tfp) tfp->close();
  delete tfp;
#endif
  delete top;
#endif
}

void handle_sigterm(int sig) {
  finish();
}

simif_emul_t::~simif_emul_t() { }

void simif_emul_t::init(int argc, char** argv, bool log) {
  // Parse args
  std::vector<std::string> args(argv + 1, argv + argc);
  const char* loadmem = NULL;
  const char* waveform = "dump.vcd";
  bool fastloadmem = false;
  bool dramsim = false;
  uint64_t memsize = 1L << 32;
  for (auto &arg: args) {
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+dramsim") == 0) {
      dramsim = true;
    }
    if (arg.find("+waveform=") == 0) {
      waveform = arg.c_str() + 10;
    }
    if (arg.find("+memsize=") == 0) {
      memsize = strtoll(arg.c_str() + 9, NULL, 10);
    }
  }

  master = std::move(std::unique_ptr<mmio_t>(new mmio_t));
  master->init(CHANNEL_DATA_BITS / 8);
  slave = std::move(std::unique_ptr<mm_t>(
    dramsim ? (mm_t*) new mm_dramsim2_t : (mm_t*) new mm_magic_t));
  slave->init(memsize, MEM_DATA_BITS / 8, 64);

  if (fastloadmem && loadmem) {
    fprintf(stdout, "[fast loadmem] %s\n", loadmem);
    void* mems[1];
    mems[0] = slave->get_data();
    ::load_mem(mems, loadmem, MEM_DATA_BITS / 8, 1);
  }

  signal(SIGTERM, handle_sigterm);

#ifdef VCS
  host = context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  is_reset = true;
  for (size_t i = 0 ; i < 10 ; i++)
    target.switch_to();
  is_reset = false;
#else
  top = new PLATFORM_TYPE;
  Verilated::commandArgs(argc, argv); // Remember args

#if VM_TRACE                         // If emul was invoked with --trace
  tfp = new VerilatedVcdC;
  Verilated::traceEverOn(true);      // Verilator must compute traced signals
  VL_PRINTF("Enabling waves...\n");
  top->trace(tfp, 99);               // Trace 99 levels of hierarchy
  tfp->open(waveform);               // Open the dump file
#endif // VM_TRACE

  top->reset = 1;
  for (size_t i = 0 ; i < 10 ; i++) ::tick();
  top->reset = 0;
#endif

  simif_t::init(argc, argv, log);
}

int simif_emul_t::finish() {
  int exitcode = simif_t::finish();
  ::finish();
  return exitcode;
}

void simif_emul_t::write(size_t addr, uint32_t data) {
  static const size_t CHANNEL_STRB = (1 << CHANNEL_STRB_BITS) - 1;
  try {
    master->write_req(addr << CHANNEL_SIZE, CHANNEL_SIZE, &data, CHANNEL_STRB);
    while(!master->write_resp()) {
#ifdef VCS
      target.switch_to();
#else
      ::tick();
#endif
    }
  } catch(std::exception &e) {
#ifdef VCS
    expect(false, e.what());
    vcs_fin = true;
    target.switch_to();
#else
    throw e;
#endif
  }
}

uint32_t simif_emul_t::read(size_t addr) {
  uint32_t data;
  try {
    master->read_req(addr << CHANNEL_SIZE, CHANNEL_SIZE);
    while(!master->read_resp(&data)) {
#ifdef VCS
      target.switch_to();
#else
      ::tick();
#endif
    }
  } catch(std::exception &e) {
#ifdef VCS
    expect(false, e.what());
    vcs_fin = true;
    target.switch_to();
#else
    throw e;
#endif
  }
  return data;
}
