// See LICENSE for license details.

#include "mmio_zynq.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include <memory>
#include <cassert>
#include <cmath>
#ifdef VCS
#include <DirectC.h>
#include "midas_context.h"
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif // VM_TRACE
#endif

void mmio_zynq_t::read_req(uint64_t addr, size_t size, size_t len) {
  mmio_req_addr_t ar(0, addr, size, len);
  this->ar.push(ar);
}

void mmio_zynq_t::write_req(uint64_t addr, size_t size, size_t len, void* data, size_t *strb) {
  int nbytes = 1 << size;

  mmio_req_addr_t aw(0, addr, size, len);
  this->aw.push(aw);

  for (int i = 0; i < len + 1; i++) {
    mmio_req_data_t w(((char*) data) + i * nbytes, strb[i], i == len);
    this->w.push(w);
  }
}

void mmio_zynq_t::tick(
  bool reset,
  bool ar_ready,
  bool aw_ready,
  bool w_ready,
  size_t r_id,
  void* r_data,
  bool r_last,
  bool r_valid,
  size_t b_id,
  bool b_valid)
{
  const bool ar_fire = !reset && ar_ready && ar_valid();
  const bool aw_fire = !reset && aw_ready && aw_valid();
  const bool w_fire = !reset && w_ready && w_valid();
  const bool r_fire = !reset && r_valid && r_ready();
  const bool b_fire = !reset && b_valid && b_ready();

  if (ar_fire) read_inflight = true;
  if (aw_fire) write_inflight = true;
  if (w_fire) this->w.pop();
  if (r_fire) {
    char* dat = (char*)malloc(dummy_data.size());
    memcpy(dat, (char*)r_data, dummy_data.size());
    mmio_resp_data_t r(r_id, dat, r_last);
    this->r.push(r);
  }
  if (b_fire) {
    this->b.push(b_id);
  }
}

bool mmio_zynq_t::read_resp(void* data) {
  if (ar.empty() || r.size() <= ar.front().len) {
    return false;
  } else {
    auto ar = this->ar.front();
    size_t word_size = 1 << ar.size;
    for (size_t i = 0 ; i <= ar.len ; i++) {
      auto r = this->r.front();
      assert(ar.id == r.id && (i < ar.len || r.last));
      memcpy(((char*)data) + i * word_size, r.data, word_size);
      free(r.data);
      this->r.pop();
    }
    this->ar.pop();
    read_inflight = false;
    return true;
  }
}

bool mmio_zynq_t::write_resp() {
  if (aw.empty() || b.empty()) {
    return false;
  } else {
    assert(aw.front().id == b.front());
    aw.pop();
    b.pop();
    write_inflight = false;
    return true;
  }
}

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
std::unique_ptr<mm_t> slave;

void* init(uint64_t memsize, bool dramsim) {
  master.reset(new mmio_zynq_t);
  slave.reset(dramsim ? (mm_t*) new mm_dramsim2_t(1 << MEM_ID_BITS) : (mm_t*) new mm_magic_t);
  slave->init(memsize, MEM_WIDTH, 64);
  return slave->get_data();
}

#ifdef VCS
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);
extern midas_context_t* host;
extern bool vcs_fin;
extern bool vcs_rst;
extern "C" {
void tick(
  vc_handle reset,
  vc_handle fin,

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
  mmio_zynq_t* m;
  assert(m = dynamic_cast<mmio_zynq_t*>(master.get()));
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
  mmio_zynq_t* m;
  assert(m = dynamic_cast<mmio_zynq_t*>(master.get()));
  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump((double) main_time);
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

  top->clock = 0;
  top->eval();

  // Slave should be ticked in clock low for comb paths
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

#if VM_TRACE
  if (tfp) tfp->dump((double) main_time);
#endif // VM_TRACE
  main_time++;
}

#endif // VCS
