#include "mmio_catapult.h"
#include "umi.h"
#include "umi_dramsim2.h"
#include <cassert>
#include <memory>
#ifdef VCS
#include <DirectC.h>
#include "midas_context.h"
#else
#include <verilated.h>
#if VM_TRACE
#include <verilated_vcd_c.h>
#endif // VM_TRACE
#endif

void mmio_catapult_t::read_req(uint64_t addr) {
  catapult_req_t r;
  r.addr = addr;
  r.wr = false;
  this->req.push(r);
}

void mmio_catapult_t::write_req(uint64_t addr, void* data) {
  catapult_req_t r;
  r.addr = addr;
  r.wr = true;
  memcpy(r.wdata, data, MMIO_WIDTH);
  this->req.push(r);
}

void mmio_catapult_t::tick(
  bool reset,
  bool req_ready,
  bool resp_valid,
  void* resp_data)
{
  const bool req_fire = !reset && req_ready && req_valid();
  const bool resp_fire = !reset && resp_ready() && resp_valid;

  if (req_fire) {
    this->req.pop();
  }

  if (resp_fire) {
    catapult_resp_t r;
    memcpy(r.rdata, resp_data, MMIO_WIDTH);
    this->resp.push(r);
  }
}

bool mmio_catapult_t::read_resp(void* data) {
  if (resp.empty()) {
    return false;
  } else {
    catapult_resp_t& r = this->resp.front();
    memcpy(data, r.rdata, MMIO_WIDTH);
    this->resp.pop();
    return true;
  }
}

bool mmio_catapult_t::write_resp() {
  return true;
}

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
std::unique_ptr<umi_t> slave;

void* init(uint64_t memsize, bool dramsim) {
  master.reset(new mmio_catapult_t);
  slave.reset(dramsim ? (umi_t*) new umi_dramsim2_t : (umi_t*) new umi_magic_t);
  slave->init(memsize, MEM_WIDTH, 64);
  return slave->get_data();
}

#ifdef VCS
static const size_t SERIAL_DATA_SIZE = SERIAL_WIDTH / sizeof(uint32_t);
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);
extern midas_context_t* host;
extern bool vcs_fin;
extern bool vcs_rst;
extern "C" {
void tick(
  vc_handle reset,
  vc_handle fin,

  vc_handle pcie_in_bits,
  vc_handle pcie_in_valid,
  vc_handle pcie_in_ready,

  vc_handle pcie_out_bits,
  vc_handle pcie_out_valid,
  vc_handle pcie_out_ready,

  vc_handle softreg_req_bits_addr,
  vc_handle softreg_req_bits_wdata,
  vc_handle softreg_req_bits_wr,
  vc_handle softreg_req_valid,
  vc_handle softreg_req_ready,

  vc_handle softreg_resp_bits_rdata,
  vc_handle softreg_resp_valid,
  vc_handle softreg_resp_ready,

  vc_handle umireq_bits_addr,
  vc_handle umireq_bits_data,
  vc_handle umireq_bits_isWrite,
  vc_handle umireq_valid,
  vc_handle umireq_ready,

  vc_handle umiresp_bits_data,
  vc_handle umiresp_valid,
  vc_handle umiresp_ready
) {
  mmio_catapult_t* m;
  assert(m = dynamic_cast<mmio_catapult_t*>(master.get()));
  uint32_t master_resp_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_resp_data[i] = vc_4stVectorRef(softreg_resp_bits_rdata)[i].d;
  }

  bool slave_resp_ready = vc_getScalar(umiresp_ready);
  bool slave_req_valid = vc_getScalar(umireq_valid);
  bool slave_req_wr = vc_getScalar(umireq_bits_isWrite);
  uint64_t slave_req_addr = 0;
  for (size_t i = 0 ; i < 2 ; i++) {
    slave_req_addr |= ((uint64_t)(vc_4stVectorRef(umireq_bits_addr)[i].d)) << (32 * i);
  }
  uint32_t slave_req_data[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    slave_req_data[i] = vc_4stVectorRef(umireq_bits_data)[i].d;
  }

  m->tick(
    vcs_rst,
    vc_getScalar(softreg_req_ready),
    vc_getScalar(softreg_resp_valid),
    master_resp_data
  );

  vc_putScalar(umireq_ready, slave->req_ready());

  slave->tick(
    vcs_rst,
    slave_req_valid,
    slave_req_wr,
    slave_req_addr,
    slave_req_data,
    slave_resp_ready
  );

  vec32 d[SERIAL_DATA_SIZE];
  for (size_t i = 0 ; i < SERIAL_DATA_SIZE ; i++) {
    d[i].c = 0;
    d[i].d = 0;
  }
  vc_put4stVector(pcie_in_bits, d);
  vc_putScalar(pcie_in_valid, false);
  vc_putScalar(pcie_out_ready, false);

  vec32 md[MASTER_DATA_SIZE];
  md[0].c = 0;
  md[0].d = m->req_addr();
  vc_put4stVector(softreg_req_bits_addr, md);
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    md[i].c = 0;
    md[i].d = ((uint32_t*) m->req_wdata())[i];
  }
  vc_put4stVector(softreg_req_bits_wdata, md);
  vc_putScalar(softreg_req_bits_wr, m->req_wr());
  vc_putScalar(softreg_req_valid,   m->req_valid());
  vc_putScalar(softreg_resp_ready,  m->resp_ready());

  vec32 sd[SLAVE_DATA_SIZE];
  for (size_t i = 0 ; i < SLAVE_DATA_SIZE ; i++) {
    sd[i].c = 0;
    sd[i].d = ((uint32_t*) slave->resp_data())[i];
  }
  vc_put4stVector(umiresp_bits_data, sd);
  vc_putScalar(umiresp_valid, slave->resp_valid());

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
  mmio_catapult_t* m;
  assert(m = dynamic_cast<mmio_catapult_t*>(master.get()));

  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump((double) main_time);
#endif // VM_TRACE
  main_time++;

  top->io_softreg_resp_ready = m->resp_ready();
  top->io_softreg_req_valid = m->req_valid();
  top->io_softreg_req_bits_addr = m->req_addr();
  top->io_softreg_req_bits_wr = m->req_wr();
#if MMIO_WIDTH > 64
  memcpy(top->io_softreg_req_bits_wdata, m->req_wdata(), MMIO_WIDTH);
#else
  memcpy(&top->io_softreg_req_bits_wdata, m->req_wdata(), MMIO_WIDTH);
#endif

  top->io_umireq_ready = slave->req_ready();
  top->io_umiresp_valid = slave->resp_valid();
  memcpy(top->io_umiresp_bits_data, slave->resp_data(), MEM_WIDTH);

  top->clock = 0;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump((double) main_time);
#endif // VM_TRACE
  main_time++;

  m->tick(
    top->reset,
    top->io_softreg_req_ready,
    top->io_softreg_resp_valid,
#if MMIO_WIDTH > 64
    top->io_softreg_resp_bits_rdata
#else
    &top->io_softreg_resp_bits_rdata
#endif
  );

  slave->tick(
    top->reset,
    top->io_umireq_valid,
    top->io_umireq_bits_isWrite,
    top->io_umireq_bits_addr,
    top->io_umireq_bits_data,
    top->io_umiresp_ready
  );
}

#endif // VCS
