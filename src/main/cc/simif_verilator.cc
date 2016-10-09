#include "simif_verilator.h"
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif
#include <signal.h>

static uint64_t main_time = 0;
static VZynqShim* top = NULL;
#if VM_TRACE
static VerilatedVcdC* tfp = new VerilatedVcdC;
#endif

double sc_time_stamp() {
  return (double) main_time;
}

void handle_sigterm(int sig) {
#if VM_TRACE
  if (tfp) tfp->close();
#endif
  delete top;
}

simif_verilator_t::~simif_verilator_t() {
  delete master;
  delete slave;
#if VM_TRACE
  if (tfp) tfp->close();
#endif
  delete top;
}

void simif_verilator_t::init(int argc, char** argv, bool log) {
  Verilated::commandArgs(argc, argv); // Remember args
  top = new VZynqShim;
 
#if VM_TRACE                          // If verilator was invoked with --trace
  Verilated::traceEverOn(true);       // Verilator must compute traced signals
  VL_PRINTF("Enabling waves...\n");
  top->trace(tfp, 99);               // Trace 99 levels of hierarchy
  tfp->open("dump.vcd");             // Open the dump file
#endif

  // bool dramsim = false;
  size_t memsize = 1 << 20;
  master = (mmio_t*) new mmio_t;
  slave = (mm_t*) new mm_magic_t;
  // slave = dramsim ? (mm_t*) new mm_dramsim2_t : (mm_t*) new mm_magic_t;
  slave->init(memsize, MEM_DATA_BITS / 8, MEM_DATA_BITS / 8);
  
  signal(SIGTERM, handle_sigterm);

  top->reset = 1;
  for (size_t i = 0 ; i < 10 ; i++) tick();
  top->reset = 0;

  simif_t::init(argc, argv, log);
}


static const size_t MEM_WIDTH = MEM_DATA_BITS / 8;
void simif_verilator_t::tick() {
  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif
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
#if CHANNEL_DATA_BITS > 64
  memcpy(top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#else
  memcpy(&top->io_master_w_bits_data, master->w_data(), MMIO_WIDTH);
#endif

  top->io_master_r_ready = master->r_ready();
  top->io_master_b_ready = master->b_ready();
  
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
    top->io_master_aw_ready,
    top->io_master_ar_ready,
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
#endif
  main_time++;
}

void simif_verilator_t::write(size_t addr, uint32_t data) {
  master->write_req(addr, &data);
  while(!master->write_resp()) tick();
}

uint32_t simif_verilator_t::read(size_t addr) {
  uint32_t data;
  master->read_req(addr);
  while(!master->read_resp(&data)) tick();
  return data;
}
