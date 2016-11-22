#include "mmio_catapult.h"

void mmio_catapult_t::read_req(uint64_t addr) {
  char* d = new char[mmio_size];
  d[0] = false;
  memcpy(d + 1, &addr, addr_size);
  mmio_data_t dd(d);
  this->req.push(dd);
}

void mmio_catapult_t::write_req(uint64_t addr, void* data) {
  char* d = new char[mmio_size];
  d[0] = true;
  memcpy(d + 1, &addr, addr_size);
  memcpy(d + 1 + addr_size, data, data_size);
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
    char* d = new char[mmio_size];
    memcpy(d, resp_data, mmio_size);
    mmio_data_t dd(d);
    this->resp.push(dd);
  }
}

bool mmio_catapult_t::read_resp(void* data) {
  if (resp.empty()) {
    return false;
  } else {
    mmio_data_t& dd = this->resp.front();
    memcpy(data, dd.data, data_size);
    this->resp.pop();
    return true;
  }
}

bool mmio_catapult_t::write_resp() {
  return req.empty();
}
