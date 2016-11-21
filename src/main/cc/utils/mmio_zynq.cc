#include "mmio_zynq.h"
#include <cassert>
#include <cmath>

void mmio_zynq_t::read_req(uint64_t addr) {
  mmio_req_addr_t ar(0, addr, size, 0);
  this->ar.push(ar);
}

void mmio_zynq_t::write_req(uint64_t addr, void* data) {
  mmio_req_addr_t aw(0, addr, size, 0);
  mmio_req_data_t w((char*) data, strb, true);
  this->aw.push(aw);
  this->w.push(w);
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
    mmio_resp_data_t r(r_id, (char*) r_data, r_last);
    this->r.push(r);
  }
  if (b_fire) {
    this->b.push(b_id);
  }
}

bool mmio_zynq_t::read_resp(void* data) {
  if (ar.empty() || r.empty()) {
    return false;
  } else {
    mmio_req_addr_t& ar = this->ar.front();
    size_t word_size = 1 << ar.size;
    for (size_t i = 0 ; i <= ar.len ; i++) {
      mmio_resp_data_t& r = this->r.front();
      assert(ar.id == r.id && (i < ar.len || r.last));
      memcpy(((char*) data) + i * word_size, r.data, word_size);
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

