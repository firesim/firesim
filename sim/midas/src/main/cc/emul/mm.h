// See LICENSE for license details.

#ifndef __EMUL_MM_H
#define __EMUL_MM_H

#include "core/config.h"

#include <cstring>
#include <queue>
#include <stdint.h>

class mm_t {
public:
  mm_t(const AXI4Config conf)
      : conf(conf), word_size(conf.beat_bytes()), data(nullptr), size(0) {}

  virtual void init(size_t sz, int lsz);

  virtual bool ar_ready() = 0;
  virtual bool aw_ready() = 0;
  virtual bool w_ready() = 0;
  virtual bool b_valid() = 0;
  virtual uint64_t b_resp() = 0;
  virtual uint64_t b_id() = 0;
  virtual bool r_valid() = 0;
  virtual uint64_t r_resp() = 0;
  virtual uint64_t r_id() = 0;
  virtual void *r_data() = 0;
  virtual bool r_last() = 0;

  virtual void tick(bool reset,

                    bool ar_valid,
                    uint64_t ar_addr,
                    uint64_t ar_id,
                    uint64_t ar_size,
                    uint64_t ar_len,

                    bool aw_valid,
                    uint64_t aw_addr,
                    uint64_t aw_id,
                    uint64_t aw_size,
                    uint64_t aw_len,

                    bool w_valid,
                    uint64_t w_strb,
                    const std::vector<uint32_t> &w_data,
                    bool w_last,

                    bool r_ready,
                    bool b_ready) = 0;

  virtual void *get_data() { return data; }
  virtual size_t get_size() { return size; }
  virtual size_t get_word_size() { return word_size; }
  virtual size_t get_line_size() { return line_size; }

  void write(uint64_t addr, const uint8_t *data, uint64_t strb, uint64_t size);
  std::vector<char> read(uint64_t addr);

  virtual ~mm_t();

  void load_mem(unsigned long start, const char *fname);

  const AXI4Config &get_config() const { return conf; }

protected:
  const AXI4Config conf;

  int word_size;
  uint8_t *data;
  size_t size;
  int line_size;
};

struct mm_rresp_t {
  uint64_t id = 0;
  std::vector<char> data;
  bool last = false;

  mm_rresp_t() = default;

  mm_rresp_t(uint64_t id, const std::vector<char> &data, bool last)
      : id(id), data(data), last(last) {}
};

class mm_magic_t final : public mm_t {
public:
  mm_magic_t(const AXI4Config &conf) : mm_t(conf), store_inflight(false) {}

  void init(size_t sz, int lsz) override;

  bool ar_ready() override { return true; }
  bool aw_ready() override { return !store_inflight; }
  bool w_ready() override { return store_inflight; }
  bool b_valid() override { return !bresp.empty(); }
  uint64_t b_resp() override { return 0; }
  uint64_t b_id() override { return b_valid() ? bresp.front() : 0; }
  bool r_valid() override { return !rresp.empty(); }
  uint64_t r_resp() override { return 0; }
  uint64_t r_id() override { return r_valid() ? rresp.front().id : 0; }
  void *r_data() override {
    return r_valid() ? &rresp.front().data[0] : &dummy_data[0];
  }
  bool r_last() override { return r_valid() ? rresp.front().last : false; }

  void tick(bool reset,

            bool ar_valid,
            uint64_t ar_addr,
            uint64_t ar_id,
            uint64_t ar_size,
            uint64_t ar_len,

            bool aw_valid,
            uint64_t aw_addr,
            uint64_t aw_id,
            uint64_t aw_size,
            uint64_t aw_len,

            bool w_valid,
            uint64_t w_strb,
            const std::vector<uint32_t> &w_data,
            bool w_last,

            bool r_ready,
            bool b_ready) override;

protected:
  bool store_inflight;
  uint64_t store_addr;
  uint64_t store_id;
  uint64_t store_size;
  uint64_t store_count;
  std::vector<char> dummy_data;
  std::queue<uint64_t> bresp;

  std::queue<mm_rresp_t> rresp;

  uint64_t cycle;
};

#endif // __EMUL_MM_H
