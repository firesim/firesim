// See LICENSE for license details.

#ifndef __MMIO_H
#define __MMIO_H

#include <cstring>
#include <queue>
#include <stddef.h>
#include <stdint.h>
#include <vector>

struct mmio_req_addr_t {
  size_t id;
  uint64_t addr;
  size_t size;
  size_t len;

  mmio_req_addr_t(size_t id_, uint64_t addr_, size_t size_, size_t len_)
      : id(id_), addr(addr_), size(size_), len(len_) {}
};

struct mmio_req_data_t {
  char *data;
  size_t strb;
  bool last;

  mmio_req_data_t(char *data_, size_t strb_, bool last_)
      : data(data_), strb(strb_), last(last_) {}
};

struct mmio_resp_data_t {
  size_t id;
  char *data;
  bool last;

  mmio_resp_data_t(size_t id_, char *data_, bool last_)
      : id(id_), data(data_), last(last_) {}
};

/**
 * @brief  staging container for driver-mastered AXI4 transactions
 *
 *  AXI4 transactions bound for the RTL-simulator context are queued up in this
 *  data structure as they wait to be driven into the verilated / or VCS design.
 *
 *  Used for CPU-mastered AXI4 (aka, DMA), and MMIO requests (see simif_t::read,
 *  simif_t::write).
 */
class mmio_t {
public:
  mmio_t(size_t size) : read_inflight(false), write_inflight(false) {
    dummy_data.resize(size);
  }

  bool aw_valid() { return !aw.empty() && !write_inflight; }
  size_t aw_id() { return aw_valid() ? aw.front().id : 0; }
  uint64_t aw_addr() { return aw_valid() ? aw.front().addr : 0; }
  size_t aw_size() { return aw_valid() ? aw.front().size : 0; }
  size_t aw_len() { return aw_valid() ? aw.front().len : 0; }

  bool ar_valid() { return !ar.empty() && !read_inflight; }
  size_t ar_id() { return ar_valid() ? ar.front().id : 0; }
  uint64_t ar_addr() { return ar_valid() ? ar.front().addr : 0; }
  size_t ar_size() { return ar_valid() ? ar.front().size : 0; }
  size_t ar_len() { return ar_valid() ? ar.front().len : 0; }

  bool w_valid() { return !w.empty(); }
  size_t w_strb() { return w_valid() ? w.front().strb : 0; }
  bool w_last() { return w_valid() ? w.front().last : false; }
  void *w_data() { return w_valid() ? w.front().data : &dummy_data[0]; }

  bool r_ready() { return read_inflight; }
  bool b_ready() { return write_inflight; }

  void tick(bool reset,
            bool ar_ready,
            bool aw_ready,
            bool w_ready,
            size_t r_id,
            const std::vector<uint32_t> &r_data,
            bool r_last,
            bool r_valid,
            size_t b_id,
            bool b_valid);

  virtual void read_req(uint64_t addr, size_t size, size_t len);
  virtual void
  write_req(uint64_t addr, size_t size, size_t len, void *data, size_t *strb);
  virtual bool read_resp(void *data);
  virtual bool write_resp();

private:
  std::queue<mmio_req_addr_t> ar;
  std::queue<mmio_req_addr_t> aw;
  std::queue<mmio_req_data_t> w;
  std::queue<mmio_resp_data_t> r;
  std::queue<size_t> b;

  bool read_inflight;
  bool write_inflight;
  std::vector<char> dummy_data;
};
void init(uint64_t memsize, bool dram);

#endif // __MMIO_H
