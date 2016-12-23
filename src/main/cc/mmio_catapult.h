#ifndef __MMIO_ZYNQ_H
#define __MMIO_ZYNQ_H

#include "mmio.h"
#include <string.h>
#include <vector>
#include <queue>

struct catapult_req_t
{
  uint64_t addr;
  char wdata[MMIO_WIDTH];
  bool wr;
};

struct catapult_resp_t
{
  char rdata[MMIO_WIDTH];
};

class mmio_catapult_t: public mmio_t
{
public:
  mmio_catapult_t() {
    dummy_data.resize(MMIO_WIDTH);
  }

  uint32_t req_addr() { return req_valid() ? req.front().addr : 0; }
  void* req_wdata() { return req_valid() ? req.front().wdata : &dummy_data[0]; }
  bool req_wr() { return req_valid() ? req.front().wr : false; }
  bool req_valid() { return !req.empty(); }
  bool resp_ready() { return true; }

  void tick
  (
    bool reset,
    bool in_ready,
    bool out_valid,
    void* out_data
  );

  virtual void read_req(uint64_t addr);
  virtual void write_req(uint64_t addr, void* data);
  virtual bool read_resp(void *data);
  virtual bool write_resp();

private:
  std::queue<catapult_req_t> req;
  std::queue<catapult_resp_t> resp;
  std::vector<char> dummy_data;
};

#endif // __MMIO_ZYNQ_H
