#include "tick_zynq.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include "mmio_zynq.h"
#include <memory>
#ifdef VCS
#include <DirectC.h>
#include "context.h"
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif // VM_TRACE
#endif

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
extern std::unique_ptr<mm_t> slave;

void init(uint64_t memsize, bool dramsim) {
  const size_t CTRL_STRB = (1 << CTRL_STRB_BITS) - 1;
  master = std::move(std::unique_ptr<mmio_t>(
    new mmio_zynq_t(CHANNEL_SIZE, CTRL_STRB, MMIO_WIDTH)));
  slave = std::move(std::unique_ptr<mm_t>(
    dramsim ? (mm_t*) new mm_dramsim2_t : (mm_t*) new mm_magic_t));
  slave->init(memsize, MEM_WIDTH, 64);
}

#ifdef VCS
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);

extern context_t* host;

extern bool vcs_fin;
extern bool vcs_err;
extern bool vcs_rst;

extern "C" {
void tick(
  vc_handle reset,
  vc_handle fin,
  vc_handle err,

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
  mmio_zynq_t* const m = dynamic_cast<mmio_zynq_t*>(master.get());
  if (!m) throw std::runtime_error("wrong master type");
  uint32_t master_r_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_r_data[i] = vc_4stVectorRef(master_r_bits_data)[i].d;
  }
  uint32_t slave_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_w_data[i] = vc_4stVectorRef(slave_w_bits_data)[i].d;
  }

  vc_putScalar(master_aw_valid, m->aw_valid());
  vc_putScalar(master_ar_valid, m->ar_valid());
  vc_putScalar(master_w_valid, m->w_valid());
  vc_putScalar(master_w_bits_last, m->w_last());
  vc_putScalar(master_r_ready, m->r_ready());
  vc_putScalar(master_b_ready, m->b_ready());

  vec32 md[MASTER_DATA_SIZE];
  md[0].c = 0;
  md[0].d = m->aw_id();
  vc_put4stVector(master_aw_bits_id, md);
  md[0].c = 0;
  md[0].d = m->aw_addr();
  vc_put4stVector(master_aw_bits_addr, md);
  md[0].c = 0;
  md[0].d = m->aw_size();
  vc_put4stVector(master_aw_bits_size, md);
  md[0].c = 0;
  md[0].d = m->aw_len();
  vc_put4stVector(master_aw_bits_len, md);
  md[0].c = 0;
  md[0].d = m->ar_id();
  vc_put4stVector(master_ar_bits_id, md);
  md[0].c = 0;
  md[0].d = m->ar_addr();
  vc_put4stVector(master_ar_bits_addr, md);
  md[0].c = 0;
  md[0].d = m->ar_size();
  vc_put4stVector(master_ar_bits_size, md);
  md[0].c = 0;
  md[0].d = m->ar_len();
  vc_put4stVector(master_ar_bits_len, md);
  md[0].c = 0;
  md[0].d = m->w_strb();
  vc_put4stVector(master_w_bits_strb, md);

  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    md[i].c = 0;
    md[i].d = ((uint32_t*) m->w_data())[i];
  }
  vc_put4stVector(master_w_bits_data, md);

  try {
    m->tick(
      vcs_rst,
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
      vcs_rst,
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
    vcs_err = true;
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

  vc_putScalar(reset, vcs_rst);
  vc_putScalar(fin, vcs_fin);
  vc_putScalar(err, vcs_err);

  main_time++;

  if (!vcs_fin) host->switch_to();
  else vcs_fin = false;
}
}

#else

extern PLATFORM_TYPE* top;
#if VM_TRACE
extern VerilatedVcdC* tfp;
#endif // VM_TRACE

void tick() {
  mmio_zynq_t* const m = dynamic_cast<mmio_zynq_t*>(master.get());
  if (!m) throw std::runtime_error("wrong master type");
  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;

  top->io_master_aw_valid = m->aw_valid();
  top->io_master_aw_bits_id = m->aw_id();
  top->io_master_aw_bits_addr = m->aw_addr();
  top->io_master_aw_bits_size = m->aw_size();
  top->io_master_aw_bits_len = m->aw_len();

  top->io_master_ar_valid = m->ar_valid();
  top->io_master_ar_bits_id = m->ar_id();
  top->io_master_ar_bits_addr = m->ar_addr();
  top->io_master_ar_bits_size = m->ar_size();
  top->io_master_ar_bits_len = m->ar_len();

  top->io_master_w_valid = m->w_valid();
  top->io_master_w_bits_strb = m->w_strb();
  top->io_master_w_bits_last = m->w_last();

  top->io_master_r_ready = m->r_ready();
  top->io_master_b_ready = m->b_ready();
#if CTRL_DATA_BITS > 64
  memcpy(top->io_master_w_bits_data, m->w_data(), MMIO_WIDTH);
#else
  memcpy(&top->io_master_w_bits_data, m->w_data(), MMIO_WIDTH);
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

  m->tick(
    top->reset,
    top->io_master_ar_ready,
    top->io_master_aw_ready,
    top->io_master_w_ready,
    top->io_master_r_bits_id,
#if CTRL_DATA_BITS > 64
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

#endif // VCS
