#ifndef __MMIO_ZYNQ_H
#define __MMIO_ZYNQ_H

#include "mmio.h"
#include <string.h>
#include <vector>
#include <queue>

struct mmio_data_t
{
  char* const data;
  mmio_data_t(char* const data_): data(data_) { }
  // ~mmio_data_t() { delete data; }
};

class mmio_catapult_t: public mmio_t
{
public:
  mmio_catapult_t(size_t mmio_size_,
                  size_t addr_size_,
                  size_t data_size_):
    mmio_size(mmio_size_), addr_size(addr_size_), data_size(data_size_)
  {
    dummy_data.resize(mmio_size);
  }

  void* req_data() { return req_valid() ? req.front().data : &dummy_data[0]; }
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
  const size_t mmio_size;
  const size_t addr_size;
  const size_t data_size;
  std::queue<mmio_data_t> req;
  std::queue<mmio_data_t> resp;
  std::vector<char> dummy_data;
};

#endif // __MMIO_ZYNQ_H
