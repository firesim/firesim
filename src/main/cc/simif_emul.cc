#include "simif_emul.h"
#ifdef VCS
#include <DirectC.h>
#include "context.h"
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
static int exitcode;

// TODO: generalize tick
#ifdef VCS
static context_t* host;
static context_t target;
static bool is_reset = false;
static bool vcs_fin = false;
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);

struct target_args_t {
  target_args_t(int c, char** v):
    argc(c), argv(v) { }
  int argc;
  char** argv;
};

extern "C" {
extern int vcs_main(int argc, char** argv);
}

int target_thread(void *arg) {
  target_args_t* targs = reinterpret_cast<target_args_t*>(arg);
  int argc = targs->argc;
  char** argv = targs->argv;
  delete targs;
  return vcs_main(argc, argv);
}

extern "C" {
void tick(
  vc_handle reset,
  vc_handle vexit,
  vc_handle vexitcode,

  vc_handle master_ar_valid,
  vc_handle master_ar_ready,
  vc_handle master_ar_bits_addr,
  vc_handle master_ar_bits_id,
  vc_handle master_ar_bits_size,
  vc_handle master_ar_bits_len,

  vc_handle master_aw_valid,
  vc_handle master_aw_ready,
  vc_handle master_aw_bits_addr,
  vc_handle master_aw_bits_id,
  vc_handle master_aw_bits_size,
  vc_handle master_aw_bits_len,

  vc_handle master_w_valid,
  vc_handle master_w_ready,
  vc_handle master_w_bits_strb,
  vc_handle master_w_bits_data,
  vc_handle master_w_bits_last,

  vc_handle master_r_valid,
  vc_handle master_r_ready,
  vc_handle master_r_bits_resp,
  vc_handle master_r_bits_id,
  vc_handle master_r_bits_data,
  vc_handle master_r_bits_last,

  vc_handle master_b_valid,
  vc_handle master_b_ready,
  vc_handle master_b_bits_resp,
  vc_handle master_b_bits_id,

  vc_handle slave_ar_valid,
  vc_handle slave_ar_ready,
  vc_handle slave_ar_bits_addr,
  vc_handle slave_ar_bits_id,
  vc_handle slave_ar_bits_size,
  vc_handle slave_ar_bits_len,

  vc_handle slave_aw_valid,
  vc_handle slave_aw_ready,
  vc_handle slave_aw_bits_addr,
  vc_handle slave_aw_bits_id,
  vc_handle slave_aw_bits_size,
  vc_handle slave_aw_bits_len,

  vc_handle slave_w_valid,
  vc_handle slave_w_ready,
  vc_handle slave_w_bits_strb,
  vc_handle slave_w_bits_data,
  vc_handle slave_w_bits_last,

  vc_handle slave_r_valid,
  vc_handle slave_r_ready,
  vc_handle slave_r_bits_resp,
  vc_handle slave_r_bits_id,
  vc_handle slave_r_bits_data,
  vc_handle slave_r_bits_last,

  vc_handle slave_b_valid,
  vc_handle slave_b_ready,
  vc_handle slave_b_bits_resp,
  vc_handle slave_b_bits_id
) {
  uint32_t master_r_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_r_data[i] = vc_4stVectorRef(master_r_bits_data)[i].d;
  }
  uint32_t slave_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_w_data[i] = vc_4stVectorRef(slave_w_bits_data)[i].d;
  }

  vc_putScalar(master_aw_valid, master->aw_valid());
  vc_putScalar(master_ar_valid, master->ar_valid());
  vc_putScalar(master_w_valid, master->w_valid());
  vc_putScalar(master_w_bits_last, master->w_last());
  vc_putScalar(master_r_ready, master->r_ready());
  vc_putScalar(master_b_ready, master->b_ready());

  vec32 md[MASTER_DATA_SIZE];
  md[0].c = 0;
  md[0].d = master->aw_id();
  vc_put4stVector(master_aw_bits_id, md);
  md[0].c = 0;
  md[0].d = master->aw_addr();
  vc_put4stVector(master_aw_bits_addr, md);
  md[0].c = 0;
  md[0].d = master->aw_size();
  vc_put4stVector(master_aw_bits_size, md);
  md[0].c = 0;
  md[0].d = master->aw_len();
  vc_put4stVector(master_aw_bits_len, md);
  md[0].c = 0;
  md[0].d = master->ar_id();
  vc_put4stVector(master_ar_bits_id, md);
  md[0].c = 0;
  md[0].d = master->ar_addr();
  vc_put4stVector(master_ar_bits_addr, md);
  md[0].c = 0;
  md[0].d = master->ar_size();
  vc_put4stVector(master_ar_bits_size, md);
  md[0].c = 0;
  md[0].d = master->ar_len();
  vc_put4stVector(master_ar_bits_len, md);
  md[0].c = 0;
  md[0].d = master->w_strb();
  vc_put4stVector(master_w_bits_strb, md);

  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    md[i].c = 0;
    md[i].d = ((uint32_t*) master->w_data())[i];
  }
  vc_put4stVector(master_w_bits_data, md);

  try {
    master->tick(
      is_reset,
      vc_getScalar(master_ar_ready),
      vc_getScalar(master_aw_ready),
      vc_getScalar(master_w_ready),
      vc_4stVectorRef(master_r_bits_id)->d,
      master_r_data,
      vc_getScalar(master_r_bits_last),
      vc_getScalar(master_r_valid),
      vc_4stVectorRef(master_b_bits_id)->d,
      vc_getScalar(master_b_valid)
    );

    slave->tick(
      is_reset,
      vc_getScalar(slave_ar_valid),
      vc_4stVectorRef(slave_ar_bits_addr)->d,
      vc_4stVectorRef(slave_ar_bits_id)->d,
      vc_4stVectorRef(slave_ar_bits_size)->d,
      vc_4stVectorRef(slave_ar_bits_len)->d,

      vc_getScalar(slave_aw_valid),
      vc_4stVectorRef(slave_aw_bits_addr)->d,
      vc_4stVectorRef(slave_aw_bits_id)->d,
      vc_4stVectorRef(slave_aw_bits_size)->d,
      vc_4stVectorRef(slave_aw_bits_len)->d,

      vc_getScalar(slave_w_valid),
      vc_4stVectorRef(slave_w_bits_strb)->d,
      slave_w_data,
      vc_getScalar(slave_w_bits_last),

      vc_getScalar(slave_r_ready),
      vc_getScalar(slave_b_ready)
    );
  } catch(std::exception &e) {
    vcs_fin = true;
    exitcode = EXIT_FAILURE;
    fprintf(stderr, "Exception in tick(): %s\n", e.what());
  }

  vc_putScalar(slave_aw_ready, slave->aw_ready());
  vc_putScalar(slave_ar_ready, slave->ar_ready());
  vc_putScalar(slave_w_ready, slave->w_ready());
  vc_putScalar(slave_b_valid, slave->b_valid());
  vc_putScalar(slave_r_valid, slave->r_valid());
  vc_putScalar(slave_r_bits_last, slave->r_last());

  vec32 sd[SLAVE_DATA_SIZE];
  sd[0].c = 0;
  sd[0].d = slave->b_id();
  vc_put4stVector(slave_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave->b_resp();
  vc_put4stVector(slave_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave->r_id();
  vc_put4stVector(slave_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave->r_resp();
  vc_put4stVector(slave_r_bits_resp, sd);
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave->r_data())[i];
  }
  vc_put4stVector(slave_r_bits_data, sd);

  vc_putScalar(vexit, vcs_fin);
  vec32 v;
  v.c = 0;
  v.d = exitcode;
  vc_put4stVector(vexitcode, &v);

  main_time++;
  vc_putScalar(reset, is_reset);

  if (!vcs_fin) host->switch_to();
}
}

#else

static VZynqShim* top = NULL;
#if VM_TRACE
static VerilatedVcdC* tfp = NULL;
#endif
double sc_time_stamp() {
  return (double) main_time;
}

void tick() {
  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;

  top->io_master_aw_valid = master->aw_valid();
  top->io_master_aw_bits_id = master->aw_id();
  top->io_master_aw_bits_addr = master->aw_addr();
  top->io_master_aw_bits_size = master->aw_size();
  top->io_master_aw_bits_len = master->aw_len();

  top->io_master_ar_valid = master->ar_valid();
  top->io_master_ar_bits_id = master->ar_id();
  top->io_master_ar_bits_addr = master->ar_addr();
  top->io_master_ar_bits_size = master->ar_size();
  top->io_master_ar_bits_len = master->ar_len();

  top->io_master_w_valid = master->w_valid();
  top->io_master_w_bits_strb = master->w_strb();
  top->io_master_w_bits_last = master->w_last();

  top->io_master_r_ready = master->r_ready();
  top->io_master_b_ready = master->b_ready();
#if CHANNEL_DATA_BITS > 64
  memcpy(top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#else
  memcpy(&top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#endif

  top->io_slave_aw_ready = slave->aw_ready();
  top->io_slave_ar_ready = slave->ar_ready();
  top->io_slave_w_ready = slave->w_ready();
  top->io_slave_b_valid = slave->b_valid();
  top->io_slave_b_bits_id = slave->b_id();
  top->io_slave_b_bits_resp = slave->b_resp();
  top->io_slave_r_valid = slave->r_valid();
  top->io_slave_r_bits_id = slave->r_id();
  top->io_slave_r_bits_resp = slave->r_resp();
  top->io_slave_r_bits_last = slave->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->io_slave_r_bits_data, slave->r_data(), MEM_WIDTH);
#else
  memcpy(&top->io_slave_r_bits_data, slave->r_data(), MEM_WIDTH);
#endif

  master->tick(
    top->reset,
    top->io_master_ar_ready,
    top->io_master_aw_ready,
    top->io_master_w_ready,
    top->io_master_r_bits_id,
#if CHANNEL_DATA_BITS > 64
    top->io_master_r_bits_data,
#else
    &top->io_master_r_bits_data,
#endif
    top->io_master_r_bits_last,
    top->io_master_r_valid,
    top->io_master_b_bits_id,
    top->io_master_b_valid
  );

  slave->tick(
    top->reset,
    top->io_slave_ar_valid,
    top->io_slave_ar_bits_addr,
    top->io_slave_ar_bits_id,
    top->io_slave_ar_bits_size,
    top->io_slave_ar_bits_len,

    top->io_slave_aw_valid,
    top->io_slave_aw_bits_addr,
    top->io_slave_aw_bits_id,
    top->io_slave_aw_bits_size,
    top->io_slave_aw_bits_len,

    top->io_slave_w_valid,
    top->io_slave_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_w_bits_data,
#else
    &top->io_slave_w_bits_data,
#endif
    top->io_slave_w_bits_last,
  
    top->io_slave_r_ready,
    top->io_slave_b_ready
  );

  top->clock = 0;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;
}

#endif

void finish() {
#ifndef VCS
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

void simif_emul_t::init(int argc, char** argv, bool log, bool fast_loadmem) {
  // Parse args
  std::vector<std::string> args(argv + 1, argv + argc);
  const char* loadmem = NULL;
  const char* waveform = "dump.vcd";
  bool dramsim = false;
  uint64_t memsize = 0xc0000000L;
  for (auto &arg: args) {
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
    if (arg.find("+waveform=") == 0) {
      waveform = arg.c_str() + 10;
    }
    if (arg.find("+dramsim") == 0) {
      dramsim = true;
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

  if (fast_loadmem && loadmem) {
    fprintf(stdout, "fast loadmem: %s\n", loadmem);
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
  top = new VZynqShim;
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

  simif_t::init(argc, argv, log, fast_loadmem);
}

int simif_emul_t::finish() {
  exitcode = simif_t::finish();
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
