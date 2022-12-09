
#include <signal.h>

#include <verilated_vcd_c.h>

#include "simif_emul_verilator.h"

simif_emul_verilator_t *emulator = nullptr;

double sc_time_stamp() { return (double)emulator->main_time; }

void handle_sigterm(int sig) {
  if (emulator) {
    emulator->finish();
  }
}

simif_emul_verilator_t::simif_emul_verilator_t(
    const std::vector<std::string> &args)
    : simif_emul_t(args), top(std::make_unique<Vverilator_top>()) {
  assert(emulator == nullptr && "a single emulator instance is required");
  emulator = this;
}

void simif_emul_verilator_t::sim_init() {
  signal(SIGTERM, handle_sigterm);
  // Remember args

#if VM_TRACE // If emul was invoked with --trace
  tfp.reset(new VerilatedVcdC);
  Verilated::traceEverOn(true); // Verilator must compute traced signals
  VL_PRINTF("Enabling waves: %s\n", waveform.c_str());
  top->trace(tfp.get(), 99);   // Trace 99 levels of hierarchy
  tfp->open(waveform.c_str()); // Open the dump file
#endif                         // VM_TRACE

  top->reset = 1;
  for (size_t i = 0; i < 10; i++)
    tick();
  top->reset = 0;
}

void simif_emul_verilator_t::advance_target() {
  int cycles_to_wait = rand_next(maximum_host_delay) + 1;
  for (int i = 0; i < cycles_to_wait; i++) {
    tick();
  }
}

void simif_emul_verilator_t::finish() {
#if VM_TRACE
  if (tfp)
    tfp->close();
#endif
}

void simif_emul_verilator_t::tick() {
  assert(cpu_managed_axi4 != nullptr);
  assert(master != nullptr);

  // ASSUMPTION: All models have *no* combinational paths through I/O
  // Step 1: Clock lo -> propagate signals between DUT and software models
  top->ctrl_aw_valid = master->aw_valid();
  top->ctrl_aw_bits_id = master->aw_id();
  top->ctrl_aw_bits_addr = master->aw_addr();
  top->ctrl_aw_bits_size = master->aw_size();
  top->ctrl_aw_bits_len = master->aw_len();

  top->ctrl_ar_valid = master->ar_valid();
  top->ctrl_ar_bits_id = master->ar_id();
  top->ctrl_ar_bits_addr = master->ar_addr();
  top->ctrl_ar_bits_size = master->ar_size();
  top->ctrl_ar_bits_len = master->ar_len();

  top->ctrl_w_valid = master->w_valid();
  top->ctrl_w_bits_strb = master->w_strb();
  top->ctrl_w_bits_last = master->w_last();

  top->ctrl_r_ready = master->r_ready();
  top->ctrl_b_ready = master->b_ready();
  memcpy(&top->ctrl_w_bits_data, master->w_data(), CTRL_BEAT_BYTES);

#ifdef CPU_MANAGED_AXI4_PRESENT
  top->cpu_managed_axi4_aw_valid = cpu_managed_axi4->aw_valid();
  top->cpu_managed_axi4_aw_bits_id = cpu_managed_axi4->aw_id();
  top->cpu_managed_axi4_aw_bits_addr = cpu_managed_axi4->aw_addr();
  top->cpu_managed_axi4_aw_bits_size = cpu_managed_axi4->aw_size();
  top->cpu_managed_axi4_aw_bits_len = cpu_managed_axi4->aw_len();

  top->cpu_managed_axi4_ar_valid = cpu_managed_axi4->ar_valid();
  top->cpu_managed_axi4_ar_bits_id = cpu_managed_axi4->ar_id();
  top->cpu_managed_axi4_ar_bits_addr = cpu_managed_axi4->ar_addr();
  top->cpu_managed_axi4_ar_bits_size = cpu_managed_axi4->ar_size();
  top->cpu_managed_axi4_ar_bits_len = cpu_managed_axi4->ar_len();

  top->cpu_managed_axi4_w_valid = cpu_managed_axi4->w_valid();
  top->cpu_managed_axi4_w_bits_strb = cpu_managed_axi4->w_strb();
  top->cpu_managed_axi4_w_bits_last = cpu_managed_axi4->w_last();

  top->cpu_managed_axi4_r_ready = cpu_managed_axi4->r_ready();
  top->cpu_managed_axi4_b_ready = cpu_managed_axi4->b_ready();
#if CPU_MANAGED_AXI4_DATA_BITS > 64
  memcpy(top->cpu_managed_axi4_w_bits_data,
         cpu_managed_axi4->w_data(),
         CPU_MANAGED_AXI4_BEAT_BYTES);
#else
  memcpy(&top->cpu_managed_axi4_w_bits_data,
         cpu_managed_axi4->w_data(),
         CPU_MANAGED_AXI4_BEAT_BYTES);
#endif
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
  top->fpga_managed_axi4_aw_ready = cpu_mem->aw_ready();
  top->fpga_managed_axi4_ar_ready = cpu_mem->ar_ready();
  top->fpga_managed_axi4_w_ready = cpu_mem->w_ready();
  top->fpga_managed_axi4_b_valid = cpu_mem->b_valid();
  top->fpga_managed_axi4_b_bits_id = cpu_mem->b_id();
  top->fpga_managed_axi4_b_bits_resp = cpu_mem->b_resp();
  top->fpga_managed_axi4_r_valid = cpu_mem->r_valid();
  top->fpga_managed_axi4_r_bits_id = cpu_mem->r_id();
  top->fpga_managed_axi4_r_bits_resp = cpu_mem->r_resp();
  top->fpga_managed_axi4_r_bits_last = cpu_mem->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->fpga_managed_axi4_r_bits_data,
         cpu_mem->r_data(),
         FPGA_MANAGED_AXI4_DATA_BITS / 8);
#else
  memcpy(&top->fpga_managed_axi4_r_bits_data,
         cpu_mem->r_data(),
         FPGA_MANAGED_AXI4_DATA_BITS / 8);
#endif
#endif // FPGA_MANAGED_AXI4_PRESENT

  top->mem_0_aw_ready = slave[0]->aw_ready();
  top->mem_0_ar_ready = slave[0]->ar_ready();
  top->mem_0_w_ready = slave[0]->w_ready();
  top->mem_0_b_valid = slave[0]->b_valid();
  top->mem_0_b_bits_id = slave[0]->b_id();
  top->mem_0_b_bits_resp = slave[0]->b_resp();
  top->mem_0_r_valid = slave[0]->r_valid();
  top->mem_0_r_bits_id = slave[0]->r_id();
  top->mem_0_r_bits_resp = slave[0]->r_resp();
  top->mem_0_r_bits_last = slave[0]->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->mem_0_r_bits_data, slave[0]->r_data(), MEM_BEAT_BYTES);
#else
  memcpy(&top->mem_0_r_bits_data, slave[0]->r_data(), MEM_BEAT_BYTES);
#endif
#ifdef MEM_HAS_CHANNEL1
  top->mem_1_aw_ready = slave[1]->aw_ready();
  top->mem_1_ar_ready = slave[1]->ar_ready();
  top->mem_1_w_ready = slave[1]->w_ready();
  top->mem_1_b_valid = slave[1]->b_valid();
  top->mem_1_b_bits_id = slave[1]->b_id();
  top->mem_1_b_bits_resp = slave[1]->b_resp();
  top->mem_1_r_valid = slave[1]->r_valid();
  top->mem_1_r_bits_id = slave[1]->r_id();
  top->mem_1_r_bits_resp = slave[1]->r_resp();
  top->mem_1_r_bits_last = slave[1]->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->mem_1_r_bits_data, slave[1]->r_data(), MEM_BEAT_BYTES);
#else
  memcpy(&top->mem_1_r_bits_data, slave[1]->r_data(), MEM_BEAT_BYTES);
#endif
#endif // MEM_HAS_CHANNEL1

#ifdef MEM_HAS_CHANNEL2
  top->mem_2_aw_ready = slave[2]->aw_ready();
  top->mem_2_ar_ready = slave[2]->ar_ready();
  top->mem_2_w_ready = slave[2]->w_ready();
  top->mem_2_b_valid = slave[2]->b_valid();
  top->mem_2_b_bits_id = slave[2]->b_id();
  top->mem_2_b_bits_resp = slave[2]->b_resp();
  top->mem_2_r_valid = slave[2]->r_valid();
  top->mem_2_r_bits_id = slave[2]->r_id();
  top->mem_2_r_bits_resp = slave[2]->r_resp();
  top->mem_2_r_bits_last = slave[2]->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->mem_2_r_bits_data, slave[2]->r_data(), MEM_BEAT_BYTES);
#else
  memcpy(&top->mem_2_r_bits_data, slave[2]->r_data(), MEM_BEAT_BYTES);
#endif
#endif // MEM_HAS_CHANNEL2

#ifdef MEM_HAS_CHANNEL3
  top->mem_3_aw_ready = slave[3]->aw_ready();
  top->mem_3_ar_ready = slave[3]->ar_ready();
  top->mem_3_w_ready = slave[3]->w_ready();
  top->mem_3_b_valid = slave[3]->b_valid();
  top->mem_3_b_bits_id = slave[3]->b_id();
  top->mem_3_b_bits_resp = slave[3]->b_resp();
  top->mem_3_r_valid = slave[3]->r_valid();
  top->mem_3_r_bits_id = slave[3]->r_id();
  top->mem_3_r_bits_resp = slave[3]->r_resp();
  top->mem_3_r_bits_last = slave[3]->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->mem_3_r_bits_data, slave[3]->r_data(), MEM_BEAT_BYTES);
#else
  memcpy(&top->mem_3_r_bits_data, slave[3]->r_data(), MEM_BEAT_BYTES);
#endif
#endif // MEM_HAS_CHANNEL3

  top->eval();
#if VM_TRACE
  if (tfp)
    tfp->dump((double)main_time);
#endif // VM_TRACE
  main_time++;

  top->clock = 0;
  top->eval(); // This shouldn't do much
#if VM_TRACE
  if (tfp)
    tfp->dump((double)main_time);
#endif // VM_TRACE
  main_time++;

  // Step 2: Clock high, tick all software models and evaluate DUT with posedge
  master->tick(top->reset,
               top->ctrl_ar_ready,
               top->ctrl_aw_ready,
               top->ctrl_w_ready,
               top->ctrl_r_bits_id,
               &top->ctrl_r_bits_data,
               top->ctrl_r_bits_last,
               top->ctrl_r_valid,
               top->ctrl_b_bits_id,
               top->ctrl_b_valid);

#ifdef CPU_MANAGED_AXI4_PRESENT
  cpu_managed_axi4->tick(top->reset,
                         top->cpu_managed_axi4_ar_ready,
                         top->cpu_managed_axi4_aw_ready,
                         top->cpu_managed_axi4_w_ready,
                         top->cpu_managed_axi4_r_bits_id,
                         &top->cpu_managed_axi4_r_bits_data,
                         top->cpu_managed_axi4_r_bits_last,
                         top->cpu_managed_axi4_r_valid,
                         top->cpu_managed_axi4_b_bits_id,
                         top->cpu_managed_axi4_b_valid);
#endif // CPU_MANAGED_AXI4_PRESENT

#ifdef FPGA_MANAGED_AXI4_PRESENT
  cpu_mem->tick(top->reset,
                top->fpga_managed_axi4_ar_valid,
                top->fpga_managed_axi4_ar_bits_addr,
                top->fpga_managed_axi4_ar_bits_id,
                top->fpga_managed_axi4_ar_bits_size,
                top->fpga_managed_axi4_ar_bits_len,

                top->fpga_managed_axi4_aw_valid,
                top->fpga_managed_axi4_aw_bits_addr,
                top->fpga_managed_axi4_aw_bits_id,
                top->fpga_managed_axi4_aw_bits_size,
                top->fpga_managed_axi4_aw_bits_len,

                top->fpga_managed_axi4_w_valid,
#if FPGA_MANAGED_AXI4_STRB_BITS > 64
                &top->fpga_managed_axi4_w_bits_strb,
#else
                top->fpga_managed_axi4_w_bits_strb,
#endif
#if FPGA_MANAGED_AXI4_DATA_BITS > 64
                &top->fpga_managed_axi4_w_bits_data,
#else
                top->fpga_managed_axi4_w_bits_data,
#endif
                top->fpga_managed_axi4_w_bits_last,

                top->fpga_managed_axi4_r_ready,
                top->fpga_managed_axi4_b_ready);
#endif // FPGA_MANAGED_AXI4_PRESENT

  slave[0]->tick(top->reset,
                 top->mem_0_ar_valid,
                 top->mem_0_ar_bits_addr,
                 top->mem_0_ar_bits_id,
                 top->mem_0_ar_bits_size,
                 top->mem_0_ar_bits_len,

                 top->mem_0_aw_valid,
                 top->mem_0_aw_bits_addr,
                 top->mem_0_aw_bits_id,
                 top->mem_0_aw_bits_size,
                 top->mem_0_aw_bits_len,

                 top->mem_0_w_valid,
                 top->mem_0_w_bits_strb,
#if MEM_DATA_BITS > 64
                 top->mem_0_w_bits_data,
#else
                 &top->mem_0_w_bits_data,
#endif
                 top->mem_0_w_bits_last,

                 top->mem_0_r_ready,
                 top->mem_0_b_ready);

#ifdef MEM_HAS_CHANNEL1
  slave[1]->tick(top->reset,
                 top->mem_1_ar_valid,
                 top->mem_1_ar_bits_addr,
                 top->mem_1_ar_bits_id,
                 top->mem_1_ar_bits_size,
                 top->mem_1_ar_bits_len,

                 top->mem_1_aw_valid,
                 top->mem_1_aw_bits_addr,
                 top->mem_1_aw_bits_id,
                 top->mem_1_aw_bits_size,
                 top->mem_1_aw_bits_len,

                 top->mem_1_w_valid,
                 top->mem_1_w_bits_strb,
#if MEM_DATA_BITS > 64
                 top->mem_1_w_bits_data,
#else
                 &top->mem_1_w_bits_data,
#endif
                 top->mem_1_w_bits_last,

                 top->mem_1_r_ready,
                 top->mem_1_b_ready);
#endif // MEM_HAS_CHANNEL1
#ifdef MEM_HAS_CHANNEL2
  slave[2]->tick(top->reset,
                 top->mem_2_ar_valid,
                 top->mem_2_ar_bits_addr,
                 top->mem_2_ar_bits_id,
                 top->mem_2_ar_bits_size,
                 top->mem_2_ar_bits_len,

                 top->mem_2_aw_valid,
                 top->mem_2_aw_bits_addr,
                 top->mem_2_aw_bits_id,
                 top->mem_2_aw_bits_size,
                 top->mem_2_aw_bits_len,

                 top->mem_2_w_valid,
                 top->mem_2_w_bits_strb,
#if MEM_DATA_BITS > 64
                 top->mem_2_w_bits_data,
#else
                 &top->mem_2_w_bits_data,
#endif
                 top->mem_2_w_bits_last,

                 top->mem_2_r_ready,
                 top->mem_2_b_ready);
#endif // MEM_HAS_CHANNEL2
#ifdef MEM_HAS_CHANNEL3
  slave[3]->tick(top->reset,
                 top->mem_3_ar_valid,
                 top->mem_3_ar_bits_addr,
                 top->mem_3_ar_bits_id,
                 top->mem_3_ar_bits_size,
                 top->mem_3_ar_bits_len,

                 top->mem_3_aw_valid,
                 top->mem_3_aw_bits_addr,
                 top->mem_3_aw_bits_id,
                 top->mem_3_aw_bits_size,
                 top->mem_3_aw_bits_len,

                 top->mem_3_w_valid,
                 top->mem_3_w_bits_strb,
#if MEM_DATA_BITS > 64
                 top->mem_3_w_bits_data,
#else
                 &top->mem_3_w_bits_data,
#endif
                 top->mem_3_w_bits_last,

                 top->mem_3_r_ready,
                 top->mem_3_b_ready);
#endif // MEM_HAS_CHANNEL3

  top->clock = 1;
  top->eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  std::vector<std::string> args(argv + 1, argv + argc);
  return simif_emul_verilator_t(args).run();
}
