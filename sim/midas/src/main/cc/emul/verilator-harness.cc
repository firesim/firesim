#include "mmio.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include <memory>
#include <cassert>
#include <cmath>
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif // VM_TRACE

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
extern std::unique_ptr<mmio_t> dma;
extern std::unique_ptr<mm_t> slave[MEM_NUM_CHANNELS];

extern Vverilator_top* top;
#if VM_TRACE
extern VerilatedVcdC* tfp;
#endif // VM_TRACE

void tick() {
  mmio_t *m, *d;
  assert(m = dynamic_cast<mmio_t*>(master.get()));
  assert(d = dynamic_cast<mmio_t*>(dma.get()));

  // ASSUMPTION: All models have *no* combinational paths through I/O
  // Step 1: Clock lo -> propagate signals between DUT and software models
  top->ctrl_aw_valid = m->aw_valid();
  top->ctrl_aw_bits_id = m->aw_id();
  top->ctrl_aw_bits_addr = m->aw_addr();
  top->ctrl_aw_bits_size = m->aw_size();
  top->ctrl_aw_bits_len = m->aw_len();

  top->ctrl_ar_valid = m->ar_valid();
  top->ctrl_ar_bits_id = m->ar_id();
  top->ctrl_ar_bits_addr = m->ar_addr();
  top->ctrl_ar_bits_size = m->ar_size();
  top->ctrl_ar_bits_len = m->ar_len();

  top->ctrl_w_valid = m->w_valid();
  top->ctrl_w_bits_strb = m->w_strb();
  top->ctrl_w_bits_last = m->w_last();

  top->ctrl_r_ready = m->r_ready();
  top->ctrl_b_ready = m->b_ready();
  memcpy(&top->ctrl_w_bits_data, m->w_data(), CTRL_BEAT_BYTES);


  top->dma_aw_valid = d->aw_valid();
  top->dma_aw_bits_id = d->aw_id();
  top->dma_aw_bits_addr = d->aw_addr();
  top->dma_aw_bits_size = d->aw_size();
  top->dma_aw_bits_len = d->aw_len();

  top->dma_ar_valid = d->ar_valid();
  top->dma_ar_bits_id = d->ar_id();
  top->dma_ar_bits_addr = d->ar_addr();
  top->dma_ar_bits_size = d->ar_size();
  top->dma_ar_bits_len = d->ar_len();

  top->dma_w_valid = d->w_valid();
  top->dma_w_bits_strb = d->w_strb();
  top->dma_w_bits_last = d->w_last();

  top->dma_r_ready = d->r_ready();
  top->dma_b_ready = d->b_ready();
#if DMA_DATA_BITS > 64
  memcpy(top->dma_w_bits_data, d->w_data(), DMA_BEAT_BYTES);
#else
  memcpy(&top->dma_w_bits_data, d->w_data(), DMA_BEAT_BYTES);
#endif

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
  if (tfp) tfp->dump((double) main_time);
#endif // VM_TRACE
  main_time++;

  top->clock = 0;
  top->eval(); // This shouldn't do much
#if VM_TRACE
  if (tfp) tfp->dump((double) main_time);
#endif // VM_TRACE
  main_time++;

  // Step 2: Clock high, tick all software models and evaluate DUT with posedge
  m->tick(
    top->reset,
    top->ctrl_ar_ready,
    top->ctrl_aw_ready,
    top->ctrl_w_ready,
    top->ctrl_r_bits_id,
    &top->ctrl_r_bits_data,
    top->ctrl_r_bits_last,
    top->ctrl_r_valid,
    top->ctrl_b_bits_id,
    top->ctrl_b_valid
  );

  d->tick(
    top->reset,
    top->dma_ar_ready,
    top->dma_aw_ready,
    top->dma_w_ready,
    top->dma_r_bits_id,
    &top->dma_r_bits_data,
    top->dma_r_bits_last,
    top->dma_r_valid,
    top->dma_b_bits_id,
    top->dma_b_valid
  );

  slave[0]->tick(
    top->reset,
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
    top->mem_0_b_ready
  );

#ifdef MEM_HAS_CHANNEL1
  slave[1]->tick(
    top->reset,
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
    top->mem_1_b_ready
  );
#endif // MEM_HAS_CHANNEL1
#ifdef MEM_HAS_CHANNEL2
  slave[2]->tick(
    top->reset,
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
    top->mem_2_b_ready
  );
#endif // MEM_HAS_CHANNEL2
#ifdef MEM_HAS_CHANNEL3
  slave[3]->tick(
    top->reset,
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
    top->mem_3_b_ready
  );
#endif // MEM_HAS_CHANNEL3

  top->clock = 1;
  top->eval();
}
