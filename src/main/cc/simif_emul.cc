#include "simif_emul.h"
#ifdef VCS
#include "midas_context.h"
#include "emul/vcs_main.h"
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#endif
#include <signal.h>
#include <memory>

uint64_t main_time = 0;
std::unique_ptr<mmio_t> master;
std::unique_ptr<mm_t> slave;

#ifdef VCS
midas_context_t* host;
midas_context_t target;
bool vcs_rst = false;
bool vcs_fin = false;
#else
PLATFORM_TYPE* top = NULL;
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

void simif_emul_t::init(int argc, char** argv, bool log) {
  // Parse args
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string waveform = "dump.vcd";
  std::string loadmem;
  bool fastloadmem = false;
  bool dramsim = false;
  uint64_t memsize = 1L << 32;
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
  }

  ::init(memsize, dramsim);

  if (slave && fastloadmem && !loadmem.empty()) {
    fprintf(stdout, "[fast loadmem] %s\n", loadmem.c_str());
    void* mems[1];
    mems[0] = slave->get_data();
    ::load_mem(mems, loadmem.c_str(), MEM_DATA_BITS / 8, 1);
  }

  signal(SIGTERM, handle_sigterm);
#ifdef VCS
  host = midas_context_t::current();
  target_args_t *targs = new target_args_t(argc, argv);
  target.init(target_thread, targs);
  vcs_rst = true;
  for (size_t i = 0 ; i < 10 ; i++)
    target.switch_to();
  vcs_rst = false;
#else
  Verilated::commandArgs(argc, argv); // Remember args

  top = new PLATFORM_TYPE;
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

  simif_t::init(argc, argv, log);
}

int simif_emul_t::finish() {
  int exitcode = simif_t::finish();
  ::finish();
  return exitcode;
}

void simif_emul_t::write(size_t addr, data_t data) {
  master->write_req(addr, &data);
  while(!master->write_resp()) {
#ifdef VCS
    target.switch_to();
#else
    ::tick();
#endif
  }
}

data_t simif_emul_t::read(size_t addr) {
  data_t data;
  master->read_req(addr);
  while(!master->read_resp(&data)) {
#ifdef VCS
    target.switch_to();
#else
    ::tick();
#endif
  }
  return data;
}
