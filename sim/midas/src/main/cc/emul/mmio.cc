#include "mmio.h"
#include <cassert>
#include <cstdlib>

void mmio_t::read_req(uint64_t addr, size_t size, size_t len) {
  mmio_req_addr_t ar(0, addr, size, len);
  this->ar.push(ar);
}

void mmio_t::write_req(
    uint64_t addr, size_t size, size_t len, void *data, size_t *strb) {
  int nbytes = 1 << size;

  mmio_req_addr_t aw(0, addr, size, len);
  this->aw.push(aw);

  for (int i = 0; i < len + 1; i++) {
    mmio_req_data_t w(((char *)data) + i * nbytes, strb[i], i == len);
    this->w.push(w);
  }
}

void mmio_t::tick(bool reset,
                  bool ar_ready,
                  bool aw_ready,
                  bool w_ready,
                  size_t r_id,
                  const std::vector<uint32_t> &r_data,
                  bool r_last,
                  bool r_valid,
                  size_t b_id,
                  bool b_valid) {
  const bool ar_fire = !reset && ar_ready && ar_valid();
  const bool aw_fire = !reset && aw_ready && aw_valid();
  const bool w_fire = !reset && w_ready && w_valid();
  const bool r_fire = !reset && r_valid && r_ready();
  const bool b_fire = !reset && b_valid && b_ready();

  if (ar_fire)
    read_inflight = true;
  if (aw_fire)
    write_inflight = true;
  if (w_fire)
    this->w.pop();
  if (r_fire) {
    char *dat = (char *)malloc(dummy_data.size());
    memcpy(dat, (const uint8_t *)r_data.data(), dummy_data.size());
    mmio_resp_data_t r(r_id, dat, r_last);
    this->r.push(r);
  }
  if (b_fire) {
    this->b.push(b_id);
  }
}

bool mmio_t::read_resp(void *data) {
  if (ar.empty() || r.size() <= ar.front().len) {
    return false;
  } else {
    auto ar = this->ar.front();
    size_t word_size = 1 << ar.size;
    for (size_t i = 0; i <= ar.len; i++) {
      auto r = this->r.front();
      assert(i < ar.len || r.last);
      memcpy(((char *)data) + i * word_size, r.data, word_size);
      free(r.data);
      this->r.pop();
    }
    this->ar.pop();
    read_inflight = false;
    return true;
  }
}

bool mmio_t::write_resp() {
  if (aw.empty() || b.empty()) {
    return false;
  } else {
    aw.pop();
    b.pop();
    write_inflight = false;
    return true;
  }
}
