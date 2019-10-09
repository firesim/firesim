// See LICENSE for license details.

#include "mm.h"
#include <iostream>
#include <fstream>
#include <cstdlib>
#include <cstring>
#include <string>
#include <cassert>

void mm_base_t::write(uint64_t addr, uint8_t *data) {
  addr %= this->size;

  uint8_t* base = this->data + addr;
  memcpy(base, data, word_size);
}

void mm_base_t::write(uint64_t addr, uint8_t *data, uint64_t strb, uint64_t size)
{
  if (addr > this->size) {
    char buf[80];
    snprintf(buf, 80, "Out-of-bounds write @ address: 0x%lx Memory size: 0x%lx\n", addr, this->size);
    throw(mm_exception(buf));
  }

  strb &= ((1L << size) - 1) << (addr % word_size);
  uint8_t *base = this->data + (addr / word_size) * word_size;
  for (int i = 0; i < word_size; i++) {
    if (strb & 1)
      base[i] = data[i];
    strb >>= 1;
  }
}


std::vector<char> mm_base_t::read(uint64_t addr)
{
  if (addr > this->size) {
    char buf[80];
    snprintf(buf, 80, "Out-of-bounds read @ address: 0x%lx Memory size: 0x%lx\n", addr, this->size);
    throw(mm_exception(buf));
  }
  uint8_t *base = this->data + addr;
  return std::vector<char>(base, base + word_size);
}

void mm_base_t::init(size_t sz, int wsz, int lsz)
{
  assert(wsz > 0 && lsz > 0 && (lsz & (lsz-1)) == 0 && lsz % wsz == 0);
  word_size = wsz;
  line_size = lsz;
  data = new uint8_t[sz];
  size = sz;
}

mm_base_t::~mm_base_t()
{
  delete [] data;
}

void mm_magic_t::init(size_t sz, int wsz, int lsz)
{
  mm_t::init(sz, wsz, lsz);
  dummy_data.resize(word_size);
}

void mm_magic_t::tick(
  bool reset,
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
  void *w_data,
  bool w_last,

  bool r_ready,
  bool b_ready)
{
  bool ar_fire = !reset && ar_valid && ar_ready();
  bool aw_fire = !reset && aw_valid && aw_ready();
  bool w_fire = !reset && w_valid && w_ready();
  bool r_fire = !reset && r_valid() && r_ready;
  bool b_fire = !reset && b_valid() && b_ready;

  if (ar_fire) {
    uint64_t start_addr = (ar_addr / word_size) * word_size;
    for (size_t i = 0; i <= ar_len; i++) {
      auto dat = read(start_addr + i * word_size);
      rresp.push(mm_rresp_t(ar_id, dat, i == ar_len));
    }
  }

  if (aw_fire) {
    store_addr = aw_addr;
    store_id = aw_id;
    store_count = aw_len + 1;
    store_size = 1 << aw_size;
    store_inflight = true;
  }

  if (w_fire) {
    write(store_addr, (uint8_t*)w_data, w_strb, store_size);
    store_addr += store_size;
    store_count--;

    if (store_count == 0) {
      store_inflight = false;
      bresp.push(store_id);
      assert(w_last);
    }
  }

  if (b_fire)
    bresp.pop();

  if (r_fire)
    rresp.pop();

  cycle++;

  if (reset) {
    while (!bresp.empty()) bresp.pop();
    while (!rresp.empty()) rresp.pop();
    cycle = 0;
  }
}

void load_mem(void** mems, const char* fn, int line_size, int nchannels)
{
  char* m;
  int start = 0;
  std::ifstream in(fn);
  if (!in)
  {
    std::cerr << "could not open " << fn << std::endl;
    exit(EXIT_FAILURE);
  }

  std::string line;
  while (std::getline(in, line))
  {
    #define parse_nibble(c) ((c) >= 'a' ? (c)-'a'+10 : (c)-'0')
    for (int i = line.length()-2, j = 0; i >= 0; i -= 2, j++) {
      char data = (parse_nibble(line[i]) << 4) | parse_nibble(line[i+1]);
      int addr = start + j;
      int channel = (addr / line_size) % nchannels;
      m = (char *) mems[channel];
      addr = (addr / line_size / nchannels) * line_size + (addr % line_size);
      m[addr] = data;
    }
    start += line.length()/2;
  }
}
