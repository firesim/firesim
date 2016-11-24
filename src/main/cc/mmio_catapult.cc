#include "mmio_catapult.h"
#include "mm.h"
#include "mm_dramsim2.h"
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

void mmio_catapult_t::read_req(uint64_t addr) {
  char* d = new char[MMIO_WIDTH];
  d[0] = false;
  memcpy(d + 1, &addr, MMIO_ADDR_WIDTH);
  mmio_data_t dd(d);
  this->req.push(dd);
}

void mmio_catapult_t::write_req(uint64_t addr, void* data) {
  char* d = new char[MMIO_WIDTH];
  d[0] = true;
  memcpy(d + 1, &addr, MMIO_ADDR_WIDTH);
  memcpy(d + 1 + MMIO_ADDR_WIDTH, data, MMIO_DATA_WIDTH);
  mmio_data_t dd(d);
  this->req.push(dd);
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
    char* d = new char[MMIO_WIDTH];
    memcpy(d, resp_data, MMIO_WIDTH);
    mmio_data_t dd(d);
    this->resp.push(dd);
  }
}

bool mmio_catapult_t::read_resp(void* data) {
  if (resp.empty()) {
    return false;
  } else {
    mmio_data_t& dd = this->resp.front();
    memcpy(data, dd.data, MMIO_DATA_WIDTH);
    this->resp.pop();
    return true;
  }
}

bool mmio_catapult_t::write_resp() {
  return req.empty();
}

extern uint64_t main_time;
extern std::unique_ptr<mmio_t> master;
extern std::unique_ptr<mm_t> slave;

void init(uint64_t memsize, bool dramsim) {
  master = std::move(std::unique_ptr<mmio_t>(new mmio_catapult_t));
#ifdef ENABLE_MEMMODEL
  // TODO: slave = ?
#endif // ENABLE_MEMMODEL
}

#ifdef VCS
static const size_t MASTER_DATA_SIZE = MMIO_WIDTH / sizeof(uint32_t);
static const size_t SLAVE_DATA_SIZE = MEM_WIDTH / sizeof(uint32_t);
extern context_t* host;
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
  vc_handle pcie_out_ready
) {
  mmio_catapult_t* const m = dynamic_cast<mmio_catapult_t*>(master.get());
  if (!m) throw std::runtime_error("wrong master type");
  uint32_t master_resp_data[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    master_resp_data[i] = vc_4stVectorRef(pcie_out_bits)[i].d;
  }

  try {
    m->tick(
      vcs_rst,
      vc_getScalar(pcie_in_ready),
      vc_getScalar(pcie_out_valid),
      master_resp_data
    );
  } catch(std::exception &e) {
    vcs_fin = true;
    fprintf(stderr, "Exception in tick(): %s\n", e.what());
  }

  vec32 md[MASTER_DATA_SIZE];
  for (size_t i = 0 ; i < MASTER_DATA_SIZE ; i++) {
    md[i].c = 0;
    md[i].d = ((uint32_t*) m->req_data())[i];
  }
  vc_put4stVector(pcie_in_bits, md);
  vc_putScalar(pcie_in_valid, m->req_valid());
  vc_putScalar(pcie_out_ready, m->resp_ready());

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
  mmio_catapult_t* const m = dynamic_cast<mmio_catapult_t*>(master.get());
  if (!m) throw std::runtime_error("wrong master type");

  top->clock = 1;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;

  top->io_pcie_out_ready = m->resp_ready();
  top->io_pcie_in_valid = m->req_valid();
#if MMIO_WIDTH > 64
  memcpy(top->io_pcie_in_bits, m->req_data(), MMIO_WIDTH);
#else
  memcpy(&top->io_pcie_in_bits, m->req_data(), MMIO_WIDTH);
#endif

  top->clock = 0;
  top->eval();
#if VM_TRACE
  if (tfp) tfp->dump(main_time);
#endif // VM_TRACE
  main_time++;

  m->tick(
    top->reset,
    top->io_pcie_in_ready,
    top->io_pcie_out_valid,
#if MMIO_WIDTH > 64
    top->io_pcie_out_bits
#else
    &top->io_pcie_out_bits
#endif

#ifdef ENABLE_MEMMODEL
// TODO:
#endif
  );
}

#endif // VCS
