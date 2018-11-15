#include "mmio_f1.h"
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

void mmio_f1_t::read_req(uint64_t addr, size_t size, size_t len) {
  mmio_req_addr_t ar(0, addr, size, len);
  this->ar.push(ar);
}

void mmio_f1_t::write_req(uint64_t addr, size_t size, size_t len, void* data, size_t *strb) {
  int nbytes = 1 << size;

  mmio_req_addr_t aw(0, addr, size, len);
  this->aw.push(aw);

  for (int i = 0; i < len + 1; i++) {
    mmio_req_data_t w(((char*) data) + i * nbytes, strb[i], i == len);
    this->w.push(w);
  }
}

void mmio_f1_t::tick(
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

bool mmio_f1_t::read_resp(void* data) {
  if (ar.empty() || r.size() <= ar.front().len) {
    return false;
  } else {
    auto ar = this->ar.front();
    size_t word_size = 1 << ar.size;
    for (size_t i = 0 ; i <= ar.len ; i++) {
      auto r = this->r.front();
      assert(i < ar.len || r.last);
      memcpy(((char*)data) + i * word_size, r.data, word_size);
      free(r.data);
      this->r.pop();
    }
    this->ar.pop();
    read_inflight = false;
    return true;
  }
}

bool mmio_f1_t::write_resp() {
  if (aw.empty() || b.empty()) {
    return false;
  } else {
    aw.pop();
    b.pop();
    write_inflight = false;
    return true;
  }
}

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
extern std::unique_ptr<mmio_t> dma;
std::unique_ptr<mm_t> slave[4];

void* init(uint64_t memsize, bool dramsim) {
  master.reset(new mmio_f1_t(MMIO_WIDTH));
  dma.reset(new mmio_f1_t(DMA_WIDTH));
  for (int mem_channel_index=0; mem_channel_index < 4; mem_channel_index++) {
    slave[mem_channel_index].reset(dramsim ? (mm_t*) new mm_dramsim2_t(1 << MEM_ID_BITS) : (mm_t*) new mm_magic_t);
    slave[mem_channel_index]->init(memsize, MEM_WIDTH, 64);
  }
  return slave[0]->get_data();
}

#ifdef VCS
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t DMA_DATA_SIZE = DMA_WIDTH / sizeof(uint32_t);
static const size_t DMA_STRB_SIZE = (DMA_WIDTH/8 + sizeof(uint32_t) - 1) / sizeof(uint32_t);
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

  vc_handle dma_ar_valid,
  vc_handle dma_ar_ready,
  vc_handle dma_ar_bits_addr,
  vc_handle dma_ar_bits_id,
  vc_handle dma_ar_bits_size,
  vc_handle dma_ar_bits_len,

  vc_handle dma_aw_valid,
  vc_handle dma_aw_ready,
  vc_handle dma_aw_bits_addr,
  vc_handle dma_aw_bits_id,
  vc_handle dma_aw_bits_size,
  vc_handle dma_aw_bits_len,

  vc_handle dma_w_valid,
  vc_handle dma_w_ready,
  vc_handle dma_w_bits_strb,
  vc_handle dma_w_bits_data,
  vc_handle dma_w_bits_last,

  vc_handle dma_r_valid,
  vc_handle dma_r_ready,
  vc_handle dma_r_bits_resp,
  vc_handle dma_r_bits_id,
  vc_handle dma_r_bits_data,
  vc_handle dma_r_bits_last,

  vc_handle dma_b_valid,
  vc_handle dma_b_ready,
  vc_handle dma_b_bits_resp,
  vc_handle dma_b_bits_id,

  vc_handle slave_0_ar_valid,
  vc_handle slave_0_ar_ready,
  vc_handle slave_0_ar_bits_addr,
  vc_handle slave_0_ar_bits_id,
  vc_handle slave_0_ar_bits_size,
  vc_handle slave_0_ar_bits_len,

  vc_handle slave_0_aw_valid,
  vc_handle slave_0_aw_ready,
  vc_handle slave_0_aw_bits_addr,
  vc_handle slave_0_aw_bits_id,
  vc_handle slave_0_aw_bits_size,
  vc_handle slave_0_aw_bits_len,

  vc_handle slave_0_w_valid,
  vc_handle slave_0_w_ready,
  vc_handle slave_0_w_bits_strb,
  vc_handle slave_0_w_bits_data,
  vc_handle slave_0_w_bits_last,

  vc_handle slave_0_r_valid,
  vc_handle slave_0_r_ready,
  vc_handle slave_0_r_bits_resp,
  vc_handle slave_0_r_bits_id,
  vc_handle slave_0_r_bits_data,
  vc_handle slave_0_r_bits_last,

  vc_handle slave_0_b_valid,
  vc_handle slave_0_b_ready,
  vc_handle slave_0_b_bits_resp,
  vc_handle slave_0_b_bits_id,

  vc_handle slave_1_ar_valid,
  vc_handle slave_1_ar_ready,
  vc_handle slave_1_ar_bits_addr,
  vc_handle slave_1_ar_bits_id,
  vc_handle slave_1_ar_bits_size,
  vc_handle slave_1_ar_bits_len,

  vc_handle slave_1_aw_valid,
  vc_handle slave_1_aw_ready,
  vc_handle slave_1_aw_bits_addr,
  vc_handle slave_1_aw_bits_id,
  vc_handle slave_1_aw_bits_size,
  vc_handle slave_1_aw_bits_len,

  vc_handle slave_1_w_valid,
  vc_handle slave_1_w_ready,
  vc_handle slave_1_w_bits_strb,
  vc_handle slave_1_w_bits_data,
  vc_handle slave_1_w_bits_last,

  vc_handle slave_1_r_valid,
  vc_handle slave_1_r_ready,
  vc_handle slave_1_r_bits_resp,
  vc_handle slave_1_r_bits_id,
  vc_handle slave_1_r_bits_data,
  vc_handle slave_1_r_bits_last,

  vc_handle slave_1_b_valid,
  vc_handle slave_1_b_ready,
  vc_handle slave_1_b_bits_resp,
  vc_handle slave_1_b_bits_id,

  vc_handle slave_2_ar_valid,
  vc_handle slave_2_ar_ready,
  vc_handle slave_2_ar_bits_addr,
  vc_handle slave_2_ar_bits_id,
  vc_handle slave_2_ar_bits_size,
  vc_handle slave_2_ar_bits_len,

  vc_handle slave_2_aw_valid,
  vc_handle slave_2_aw_ready,
  vc_handle slave_2_aw_bits_addr,
  vc_handle slave_2_aw_bits_id,
  vc_handle slave_2_aw_bits_size,
  vc_handle slave_2_aw_bits_len,

  vc_handle slave_2_w_valid,
  vc_handle slave_2_w_ready,
  vc_handle slave_2_w_bits_strb,
  vc_handle slave_2_w_bits_data,
  vc_handle slave_2_w_bits_last,

  vc_handle slave_2_r_valid,
  vc_handle slave_2_r_ready,
  vc_handle slave_2_r_bits_resp,
  vc_handle slave_2_r_bits_id,
  vc_handle slave_2_r_bits_data,
  vc_handle slave_2_r_bits_last,

  vc_handle slave_2_b_valid,
  vc_handle slave_2_b_ready,
  vc_handle slave_2_b_bits_resp,
  vc_handle slave_2_b_bits_id,

  vc_handle slave_3_ar_valid,
  vc_handle slave_3_ar_ready,
  vc_handle slave_3_ar_bits_addr,
  vc_handle slave_3_ar_bits_id,
  vc_handle slave_3_ar_bits_size,
  vc_handle slave_3_ar_bits_len,

  vc_handle slave_3_aw_valid,
  vc_handle slave_3_aw_ready,
  vc_handle slave_3_aw_bits_addr,
  vc_handle slave_3_aw_bits_id,
  vc_handle slave_3_aw_bits_size,
  vc_handle slave_3_aw_bits_len,

  vc_handle slave_3_w_valid,
  vc_handle slave_3_w_ready,
  vc_handle slave_3_w_bits_strb,
  vc_handle slave_3_w_bits_data,
  vc_handle slave_3_w_bits_last,

  vc_handle slave_3_r_valid,
  vc_handle slave_3_r_ready,
  vc_handle slave_3_r_bits_resp,
  vc_handle slave_3_r_bits_id,
  vc_handle slave_3_r_bits_data,
  vc_handle slave_3_r_bits_last,

  vc_handle slave_3_b_valid,
  vc_handle slave_3_b_ready,
  vc_handle slave_3_b_bits_resp,
  vc_handle slave_3_b_bits_id
) {
  mmio_f1_t *m, *d;
  assert(m = dynamic_cast<mmio_f1_t*>(master.get()));
  assert(d = dynamic_cast<mmio_f1_t*>(dma.get()));
  assert(DMA_STRB_SIZE <= 2);

  uint32_t master_r_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_r_data[i] = vc_4stVectorRef(master_r_bits_data)[i].d;
  }
  uint32_t dma_r_data[DMA_DATA_SIZE];
  for (size_t i = 0 ; i < DMA_DATA_SIZE ; i++) {
    dma_r_data[i] = vc_4stVectorRef(dma_r_bits_data)[i].d;
  }
  uint32_t slave_0_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_0_w_data[i] = vc_4stVectorRef(slave_0_w_bits_data)[i].d;
  }

  uint32_t slave_1_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_1_w_data[i] = vc_4stVectorRef(slave_1_w_bits_data)[i].d;
  }

  uint32_t slave_2_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_2_w_data[i] = vc_4stVectorRef(slave_2_w_bits_data)[i].d;
  }

  uint32_t slave_3_w_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_3_w_data[i] = vc_4stVectorRef(slave_3_w_bits_data)[i].d;
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

  vc_putScalar(dma_aw_valid, d->aw_valid());
  vc_putScalar(dma_ar_valid, d->ar_valid());
  vc_putScalar(dma_w_valid, d->w_valid());
  vc_putScalar(dma_w_bits_last, d->w_last());
  vc_putScalar(dma_r_ready, d->r_ready());
  vc_putScalar(dma_b_ready, d->b_ready());

  vec32 dd[DMA_DATA_SIZE];
  dd[0].c = 0;
  dd[0].d = d->aw_id();
  vc_put4stVector(dma_aw_bits_id, dd);
  dd[0].c = 0;
  dd[0].d = d->aw_addr();
  vc_put4stVector(dma_aw_bits_addr, dd);
  dd[0].c = 0;
  dd[0].d = d->aw_size();
  vc_put4stVector(dma_aw_bits_size, dd);
  dd[0].c = 0;
  dd[0].d = d->aw_len();
  vc_put4stVector(dma_aw_bits_len, dd);
  dd[0].c = 0;
  dd[0].d = d->ar_id();
  vc_put4stVector(dma_ar_bits_id, dd);
  dd[0].c = 0;
  dd[0].d = d->ar_addr();
  vc_put4stVector(dma_ar_bits_addr, dd);
  dd[0].c = 0;
  dd[0].d = d->ar_size();
  vc_put4stVector(dma_ar_bits_size, dd);
  dd[0].c = 0;
  dd[0].d = d->ar_len();
  vc_put4stVector(dma_ar_bits_len, dd);

  auto strb = d->w_strb();
  for (size_t i = 0 ; i < DMA_STRB_SIZE ; i++) {
    dd[i].c = 0;
    dd[i].d = ((uint32_t*)(&strb))[i];
  }
  vc_put4stVector(dma_w_bits_strb, dd);

  for (size_t i = 0 ; i < DMA_DATA_SIZE ; i++) {
    dd[i].c = 0;
    dd[i].d = ((uint32_t*) d->w_data())[i];
  }
  vc_put4stVector(dma_w_bits_data, dd);

  d->tick(
    vcs_rst,
    vc_getScalar(dma_ar_ready),
    vc_getScalar(dma_aw_ready),
    vc_getScalar(dma_w_ready),
    vc_4stVectorRef(dma_r_bits_id)->d,
    dma_r_data,
    vc_getScalar(dma_r_bits_last),
    vc_getScalar(dma_r_valid),
    vc_4stVectorRef(dma_b_bits_id)->d,
    vc_getScalar(dma_b_valid)
  );

  slave[0]->tick(
    vcs_rst,
    vc_getScalar(slave_0_ar_valid),
    vc_4stVectorRef(slave_0_ar_bits_addr)->d,
    vc_4stVectorRef(slave_0_ar_bits_id)->d,
    vc_4stVectorRef(slave_0_ar_bits_size)->d,
    vc_4stVectorRef(slave_0_ar_bits_len)->d,

    vc_getScalar(slave_0_aw_valid),
    vc_4stVectorRef(slave_0_aw_bits_addr)->d,
    vc_4stVectorRef(slave_0_aw_bits_id)->d,
    vc_4stVectorRef(slave_0_aw_bits_size)->d,
    vc_4stVectorRef(slave_0_aw_bits_len)->d,

    vc_getScalar(slave_0_w_valid),
    vc_4stVectorRef(slave_0_w_bits_strb)->d,
    slave_0_w_data,
    vc_getScalar(slave_0_w_bits_last),

    vc_getScalar(slave_0_r_ready),
    vc_getScalar(slave_0_b_ready)
  );

  slave[1]->tick(
    vcs_rst,
    vc_getScalar(slave_1_ar_valid),
    vc_4stVectorRef(slave_1_ar_bits_addr)->d,
    vc_4stVectorRef(slave_1_ar_bits_id)->d,
    vc_4stVectorRef(slave_1_ar_bits_size)->d,
    vc_4stVectorRef(slave_1_ar_bits_len)->d,

    vc_getScalar(slave_1_aw_valid),
    vc_4stVectorRef(slave_1_aw_bits_addr)->d,
    vc_4stVectorRef(slave_1_aw_bits_id)->d,
    vc_4stVectorRef(slave_1_aw_bits_size)->d,
    vc_4stVectorRef(slave_1_aw_bits_len)->d,

    vc_getScalar(slave_1_w_valid),
    vc_4stVectorRef(slave_1_w_bits_strb)->d,
    slave_1_w_data,
    vc_getScalar(slave_1_w_bits_last),

    vc_getScalar(slave_1_r_ready),
    vc_getScalar(slave_1_b_ready)
  );


  slave[2]->tick(
    vcs_rst,
    vc_getScalar(slave_2_ar_valid),
    vc_4stVectorRef(slave_2_ar_bits_addr)->d,
    vc_4stVectorRef(slave_2_ar_bits_id)->d,
    vc_4stVectorRef(slave_2_ar_bits_size)->d,
    vc_4stVectorRef(slave_2_ar_bits_len)->d,

    vc_getScalar(slave_2_aw_valid),
    vc_4stVectorRef(slave_2_aw_bits_addr)->d,
    vc_4stVectorRef(slave_2_aw_bits_id)->d,
    vc_4stVectorRef(slave_2_aw_bits_size)->d,
    vc_4stVectorRef(slave_2_aw_bits_len)->d,

    vc_getScalar(slave_2_w_valid),
    vc_4stVectorRef(slave_2_w_bits_strb)->d,
    slave_2_w_data,
    vc_getScalar(slave_2_w_bits_last),

    vc_getScalar(slave_2_r_ready),
    vc_getScalar(slave_2_b_ready)
  );


  slave[3]->tick(
    vcs_rst,
    vc_getScalar(slave_3_ar_valid),
    vc_4stVectorRef(slave_3_ar_bits_addr)->d,
    vc_4stVectorRef(slave_3_ar_bits_id)->d,
    vc_4stVectorRef(slave_3_ar_bits_size)->d,
    vc_4stVectorRef(slave_3_ar_bits_len)->d,

    vc_getScalar(slave_3_aw_valid),
    vc_4stVectorRef(slave_3_aw_bits_addr)->d,
    vc_4stVectorRef(slave_3_aw_bits_id)->d,
    vc_4stVectorRef(slave_3_aw_bits_size)->d,
    vc_4stVectorRef(slave_3_aw_bits_len)->d,

    vc_getScalar(slave_3_w_valid),
    vc_4stVectorRef(slave_3_w_bits_strb)->d,
    slave_3_w_data,
    vc_getScalar(slave_3_w_bits_last),

    vc_getScalar(slave_3_r_ready),
    vc_getScalar(slave_3_b_ready)
  );

  vc_putScalar(slave_0_aw_ready, slave[0]->aw_ready());
  vc_putScalar(slave_0_ar_ready, slave[0]->ar_ready());
  vc_putScalar(slave_0_w_ready, slave[0]->w_ready());
  vc_putScalar(slave_0_b_valid, slave[0]->b_valid());
  vc_putScalar(slave_0_r_valid, slave[0]->r_valid());
  vc_putScalar(slave_0_r_bits_last, slave[0]->r_last());

  vc_putScalar(slave_1_aw_ready, slave[1]->aw_ready());
  vc_putScalar(slave_1_ar_ready, slave[1]->ar_ready());
  vc_putScalar(slave_1_w_ready, slave[1]->w_ready());
  vc_putScalar(slave_1_b_valid, slave[1]->b_valid());
  vc_putScalar(slave_1_r_valid, slave[1]->r_valid());
  vc_putScalar(slave_1_r_bits_last, slave[1]->r_last());

  vc_putScalar(slave_2_aw_ready, slave[2]->aw_ready());
  vc_putScalar(slave_2_ar_ready, slave[2]->ar_ready());
  vc_putScalar(slave_2_w_ready, slave[2]->w_ready());
  vc_putScalar(slave_2_b_valid, slave[2]->b_valid());
  vc_putScalar(slave_2_r_valid, slave[2]->r_valid());
  vc_putScalar(slave_2_r_bits_last, slave[2]->r_last());

  vc_putScalar(slave_3_aw_ready, slave[3]->aw_ready());
  vc_putScalar(slave_3_ar_ready, slave[3]->ar_ready());
  vc_putScalar(slave_3_w_ready, slave[3]->w_ready());
  vc_putScalar(slave_3_b_valid, slave[3]->b_valid());
  vc_putScalar(slave_3_r_valid, slave[3]->r_valid());
  vc_putScalar(slave_3_r_bits_last, slave[3]->r_last());


  vec32 sd[SLAVE_DATA_SIZE];
  sd[0].c = 0;
  sd[0].d = slave[0]->b_id();
  vc_put4stVector(slave_0_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[0]->b_resp();
  vc_put4stVector(slave_0_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[0]->r_id();
  vc_put4stVector(slave_0_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[0]->r_resp();
  vc_put4stVector(slave_0_r_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[1]->b_id();
  vc_put4stVector(slave_1_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[1]->b_resp();
  vc_put4stVector(slave_1_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[1]->r_id();
  vc_put4stVector(slave_1_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[1]->r_resp();
  vc_put4stVector(slave_1_r_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[2]->b_id();
  vc_put4stVector(slave_2_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[2]->b_resp();
  vc_put4stVector(slave_2_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[2]->r_id();
  vc_put4stVector(slave_2_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[2]->r_resp();
  vc_put4stVector(slave_2_r_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[3]->b_id();
  vc_put4stVector(slave_3_b_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[3]->b_resp();
  vc_put4stVector(slave_3_b_bits_resp, sd);
  sd[0].c = 0;
  sd[0].d = slave[3]->r_id();
  vc_put4stVector(slave_3_r_bits_id, sd);
  sd[0].c = 0;
  sd[0].d = slave[3]->r_resp();
  vc_put4stVector(slave_3_r_bits_resp, sd);
 
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave[0]->r_data())[i];
  }
  vc_put4stVector(slave_0_r_bits_data, sd);
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave[1]->r_data())[i];
  }
  vc_put4stVector(slave_1_r_bits_data, sd);
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave[2]->r_data())[i];
  }
  vc_put4stVector(slave_2_r_bits_data, sd);
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave[3]->r_data())[i];
  }
  vc_put4stVector(slave_3_r_bits_data, sd);
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
  mmio_f1_t *m, *d;
  assert(m = dynamic_cast<mmio_f1_t*>(master.get()));
  assert(d = dynamic_cast<mmio_f1_t*>(dma.get()));

  // ASSUMPTION: All models have *no* combinational paths through I/O
  // Step 1: Clock lo -> propagate signals between DUT and software models
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


  top->io_dma_aw_valid = d->aw_valid();
  top->io_dma_aw_bits_id = d->aw_id();
  top->io_dma_aw_bits_addr = d->aw_addr();
  top->io_dma_aw_bits_size = d->aw_size();
  top->io_dma_aw_bits_len = d->aw_len();

  top->io_dma_ar_valid = d->ar_valid();
  top->io_dma_ar_bits_id = d->ar_id();
  top->io_dma_ar_bits_addr = d->ar_addr();
  top->io_dma_ar_bits_size = d->ar_size();
  top->io_dma_ar_bits_len = d->ar_len();

  top->io_dma_w_valid = d->w_valid();
  top->io_dma_w_bits_strb = d->w_strb();
  top->io_dma_w_bits_last = d->w_last();

  top->io_dma_r_ready = d->r_ready();
  top->io_dma_b_ready = d->b_ready();
#if DMA_DATA_BITS > 64
  memcpy(top->io_dma_w_bits_data, d->w_data(), DMA_WIDTH);
#else
  memcpy(&top->io_dma_w_bits_data, d->w_data(), DMA_WIDTH);
#endif

  top->io_slave_0_aw_ready = slave[0]->aw_ready();
  top->io_slave_0_ar_ready = slave[0]->ar_ready();
  top->io_slave_0_w_ready = slave[0]->w_ready();
  top->io_slave_0_b_valid = slave[0]->b_valid();
  top->io_slave_0_b_bits_id = slave[0]->b_id();
  top->io_slave_0_b_bits_resp = slave[0]->b_resp();
  top->io_slave_0_r_valid = slave[0]->r_valid();
  top->io_slave_0_r_bits_id = slave[0]->r_id();
  top->io_slave_0_r_bits_resp = slave[0]->r_resp();
  top->io_slave_0_r_bits_last = slave[0]->r_last();
  top->io_slave_1_aw_ready = slave[1]->aw_ready();
  top->io_slave_1_ar_ready = slave[1]->ar_ready();
  top->io_slave_1_w_ready = slave[1]->w_ready();
  top->io_slave_1_b_valid = slave[1]->b_valid();
  top->io_slave_1_b_bits_id = slave[1]->b_id();
  top->io_slave_1_b_bits_resp = slave[1]->b_resp();
  top->io_slave_1_r_valid = slave[1]->r_valid();
  top->io_slave_1_r_bits_id = slave[1]->r_id();
  top->io_slave_1_r_bits_resp = slave[1]->r_resp();
  top->io_slave_1_r_bits_last = slave[1]->r_last();
  top->io_slave_2_aw_ready = slave[2]->aw_ready();
  top->io_slave_2_ar_ready = slave[2]->ar_ready();
  top->io_slave_2_w_ready = slave[2]->w_ready();
  top->io_slave_2_b_valid = slave[2]->b_valid();
  top->io_slave_2_b_bits_id = slave[2]->b_id();
  top->io_slave_2_b_bits_resp = slave[2]->b_resp();
  top->io_slave_2_r_valid = slave[2]->r_valid();
  top->io_slave_2_r_bits_id = slave[2]->r_id();
  top->io_slave_2_r_bits_resp = slave[2]->r_resp();
  top->io_slave_2_r_bits_last = slave[2]->r_last();
  top->io_slave_3_aw_ready = slave[3]->aw_ready();
  top->io_slave_3_ar_ready = slave[3]->ar_ready();
  top->io_slave_3_w_ready = slave[3]->w_ready();
  top->io_slave_3_b_valid = slave[3]->b_valid();
  top->io_slave_3_b_bits_id = slave[3]->b_id();
  top->io_slave_3_b_bits_resp = slave[3]->b_resp();
  top->io_slave_3_r_valid = slave[3]->r_valid();
  top->io_slave_3_r_bits_id = slave[3]->r_id();
  top->io_slave_3_r_bits_resp = slave[3]->r_resp();
  top->io_slave_3_r_bits_last = slave[3]->r_last();
#if MEM_DATA_BITS > 64
  memcpy(top->io_slave_0_r_bits_data, slave[0]->r_data(), MEM_WIDTH);
  memcpy(top->io_slave_1_r_bits_data, slave[1]->r_data(), MEM_WIDTH);
  memcpy(top->io_slave_2_r_bits_data, slave[2]->r_data(), MEM_WIDTH);
  memcpy(top->io_slave_3_r_bits_data, slave[3]->r_data(), MEM_WIDTH);
#else
  memcpy(&top->io_slave_0_r_bits_data, slave[0]->r_data(), MEM_WIDTH);
  memcpy(&top->io_slave_1_r_bits_data, slave[1]->r_data(), MEM_WIDTH);
  memcpy(&top->io_slave_2_r_bits_data, slave[2]->r_data(), MEM_WIDTH);
  memcpy(&top->io_slave_3_r_bits_data, slave[3]->r_data(), MEM_WIDTH);
#endif
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

  d->tick(
    top->reset,
    top->io_dma_ar_ready,
    top->io_dma_aw_ready,
    top->io_dma_w_ready,
    top->io_dma_r_bits_id,
#if DMA_DATA_BITS > 64
    top->io_dma_r_bits_data,
#else
    &top->io_dma_r_bits_data,
#endif
    top->io_dma_r_bits_last,
    top->io_dma_r_valid,
    top->io_dma_b_bits_id,
    top->io_dma_b_valid
  );

  slave[0]->tick(
    top->reset,
    top->io_slave_0_ar_valid,
    top->io_slave_0_ar_bits_addr,
    top->io_slave_0_ar_bits_id,
    top->io_slave_0_ar_bits_size,
    top->io_slave_0_ar_bits_len,

    top->io_slave_0_aw_valid,
    top->io_slave_0_aw_bits_addr,
    top->io_slave_0_aw_bits_id,
    top->io_slave_0_aw_bits_size,
    top->io_slave_0_aw_bits_len,

    top->io_slave_0_w_valid,
    top->io_slave_0_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_0_w_bits_data,
#else
    &top->io_slave_0_w_bits_data,
#endif
    top->io_slave_0_w_bits_last,
  
    top->io_slave_0_r_ready,
    top->io_slave_0_b_ready
  );
  slave[1]->tick(
    top->reset,
    top->io_slave_1_ar_valid,
    top->io_slave_1_ar_bits_addr,
    top->io_slave_1_ar_bits_id,
    top->io_slave_1_ar_bits_size,
    top->io_slave_1_ar_bits_len,

    top->io_slave_1_aw_valid,
    top->io_slave_1_aw_bits_addr,
    top->io_slave_1_aw_bits_id,
    top->io_slave_1_aw_bits_size,
    top->io_slave_1_aw_bits_len,

    top->io_slave_1_w_valid,
    top->io_slave_1_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_1_w_bits_data,
#else
    &top->io_slave_1_w_bits_data,
#endif
    top->io_slave_1_w_bits_last,
  
    top->io_slave_1_r_ready,
    top->io_slave_1_b_ready
  );
  slave[2]->tick(
    top->reset,
    top->io_slave_2_ar_valid,
    top->io_slave_2_ar_bits_addr,
    top->io_slave_2_ar_bits_id,
    top->io_slave_2_ar_bits_size,
    top->io_slave_2_ar_bits_len,

    top->io_slave_2_aw_valid,
    top->io_slave_2_aw_bits_addr,
    top->io_slave_2_aw_bits_id,
    top->io_slave_2_aw_bits_size,
    top->io_slave_2_aw_bits_len,

    top->io_slave_2_w_valid,
    top->io_slave_2_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_2_w_bits_data,
#else
    &top->io_slave_2_w_bits_data,
#endif
    top->io_slave_2_w_bits_last,
  
    top->io_slave_2_r_ready,
    top->io_slave_2_b_ready
  );
  slave[3]->tick(
    top->reset,
    top->io_slave_3_ar_valid,
    top->io_slave_3_ar_bits_addr,
    top->io_slave_3_ar_bits_id,
    top->io_slave_3_ar_bits_size,
    top->io_slave_3_ar_bits_len,

    top->io_slave_3_aw_valid,
    top->io_slave_3_aw_bits_addr,
    top->io_slave_3_aw_bits_id,
    top->io_slave_3_aw_bits_size,
    top->io_slave_3_aw_bits_len,

    top->io_slave_3_w_valid,
    top->io_slave_3_w_bits_strb,
#if MEM_DATA_BITS > 64
    top->io_slave_3_w_bits_data,
#else
    &top->io_slave_3_w_bits_data,
#endif
    top->io_slave_3_w_bits_last,
  
    top->io_slave_3_r_ready,
    top->io_slave_3_b_ready
  );

  top->clock = 1;
  top->eval();
}

#endif // VCS
