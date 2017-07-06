#include <algorithm>
#include <stdio.h>

#include "sim_mem.h"
// TODO: support multi channels

sim_mem_t::sim_mem_t(simif_t* sim, int argc, char** argv): endpoint_t(sim) {
#ifdef NASTIWIDGET_0
  std::vector<std::string> args(argv + 1, argv + argc);
  bool dramsim = false;
  uint64_t memsize = 1L << 26; // 64 KB
  const char* loadmem = NULL;
  for (auto &arg: args) {
    if (arg.find("+dramsim") == 0) {
      dramsim = true;
    }
    else if (arg.find("+memsize=") == 0) {
      memsize = strtoll(arg.c_str() + 9, NULL, 10);
    }
    else if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
  }
  mem = dramsim ? (mm_t*) new mm_dramsim2_t : (mm_t*) new mm_magic_t;
  mem->init(memsize, MEM_DATA_BITS / 8, 64);
  if (loadmem) {
    fprintf(stderr, "[sw loadmem] %s\n", loadmem);
    void* mems[1];
    mems[0] = mem->get_data();
    ::load_mem(mems, loadmem, MEM_DATA_BITS / 8, 1);
  }
#endif
}

void sim_mem_t::delta(size_t t) {
#ifdef NASTIWIDGET_0
  write(NASTIWIDGET_0(delta), t);
#endif
}

bool sim_mem_t::stall() {
#ifdef NASTIWIDGET_0
  return read(NASTIWIDGET_0(stall));
#else
  return false;
#endif
}

bool sim_mem_t::done() {
#ifdef NASTIWIDGET_0
  return read(NASTIWIDGET_0(done));
#else
  return true;
#endif
}

const uint64_t addr_mask = (1L << MEM_ADDR_BITS) - 1;
const data_t id_mask = (1 << MEM_ID_BITS) - 1;
const data_t size_mask = (1 << MEM_SIZE_BITS) - 1;
const data_t len_mask = (1 << MEM_LEN_BITS) - 1;
const data_t strb_mask = (1 << MEM_STRB_BITS) - 1;

void sim_mem_t::recv(sim_mem_data_t& data) {
#ifdef NASTIWIDGET_0
  data_t valid = read(NASTIWIDGET_0(valid));
  data.ar.valid = (valid >> 4) & 0x1;
  data.aw.valid = (valid >> 3) & 0x1;
  data.w.valid = (valid >> 2) & 0x1;
  data.r.ready = (valid >> 1) & 0x1;
  data.b.ready = valid & 0x1;

  if (data.ar.fire()) {
#ifdef NASTIWIDGET_0_ar_bits
    data_t bits = read(NASTIWIDGET_0(ar_bits));
    data.ar.addr = (bits >> (MEM_ID_BITS + MEM_SIZE_BITS + MEM_LEN_BITS)) & addr_mask;
#else
    data_t bits = read(NASTIWIDGET_0(ar_meta));
    data.ar.addr = read(NASTIWIDGET_0(ar_addr));
#endif
    data.ar.id = (bits >> (MEM_SIZE_BITS + MEM_LEN_BITS)) & id_mask;
    data.ar.size = (bits >> MEM_LEN_BITS) & size_mask;
    data.ar.len = bits & len_mask;
  }

  if (data.aw.fire()) {
#ifdef NASTIWIDGET_0_aw_bits
    data_t bits = read(NASTIWIDGET_0(aw_bits));
    data.aw.addr = (bits >> (MEM_ID_BITS + MEM_SIZE_BITS + MEM_LEN_BITS)) & addr_mask;
#else
    data_t bits = read(NASTIWIDGET_0(aw_meta));
    data.aw.addr = read(NASTIWIDGET_0(aw_addr));
#endif
    data.aw.id = (bits >> (MEM_SIZE_BITS + MEM_LEN_BITS)) & id_mask;
    data.aw.size = (bits >> MEM_LEN_BITS) & size_mask;
    data.aw.len = bits & len_mask;
  }
  if (data.w.fire()) {
    data_t meta = read(NASTIWIDGET_0(w_meta));
    data.w.strb = (meta >> 1) & strb_mask;
    data.w.last = meta & 0x1;
    for (size_t i = 0; i < MEM_CHUNKS; i++) {
      data.w.data[i] = read(NASTIWIDGET_0(w_data[i]));
    }
  }
#endif
}

void sim_mem_t::send(sim_mem_data_t& data) {
#ifdef NASTIWIDGET_0
  if (data.r.fire()) {
    data_t meta = 0x0;
    meta |= ((data_t)data.r.id) << (MEM_RESP_BITS + 1);
    meta |= ((data_t)data.r.resp) << 1;
    meta |= ((data_t)data.r.last);
    write(NASTIWIDGET_0(r_meta), meta);
    for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
      write(NASTIWIDGET_0(r_data[i]), data.r.data[i]);
    }
  }
  if (data.b.fire()) {
    data_t meta = 0x0;
    meta |= ((data_t)data.b.id) << MEM_RESP_BITS;
    meta |= ((data_t)data.b.resp);
    write(NASTIWIDGET_0(b_meta), meta);
  }

  data_t ready = 0x0;
  ready |= ((data_t)data.ar.ready) << 4;
  ready |= ((data_t)data.aw.ready) << 3;
  ready |= ((data_t)data.w.ready) << 2;
  ready |= ((data_t)data.r.valid) << 1;
  ready |= ((data_t)data.b.valid);
  write(NASTIWIDGET_0(ready), ready);
#endif
}

void sim_mem_t::tick() {
#ifdef NASTIWIDGET_0
  bool _stall = this->stall();
  static size_t num_reads = 0;
  static size_t num_writes = 0;
  static sim_mem_data_t data;
  if (_stall || num_reads || num_writes) {
    data.ar.ready = mem->ar_ready();
    data.aw.ready = mem->aw_ready();
    data.w.ready = mem->w_ready();

    this->recv(data);

    if (data.ar.fire()) num_reads++;
    if (data.aw.fire()) num_writes++;

    mem->tick(
      false,
      data.ar.valid,
      data.ar.addr,
      data.ar.id,
      data.ar.size,
      data.ar.len,

      data.aw.valid,
      data.aw.addr,
      data.aw.id,
      data.aw.size,
      data.aw.len,

      data.w.valid,
      data.w.strb,
      data.w.data,
      data.w.last,

      data.r.ready,
      data.b.ready
    );

    data.b.id = mem->b_id();
    data.b.resp = mem->b_resp(); 
    data.b.valid = mem->b_valid();

    data.r.id = mem->r_id();
    data.r.resp = mem->r_resp();
    data.r.last = mem->r_last();
    data.r.valid = mem->r_valid();
    if (data.r.fire()) {
      data_t* r_data = (data_t*) mem->r_data();
      for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
        data.r.data[i] = r_data[i];
      }
    }

    this->send(data);

    if (_stall) this->delta(1);
    if (data.r.fire() && data.r.last) num_reads--;
    if (data.b.fire()) num_writes--;
  }
#endif
}

void sim_mem_t::write_mem(uint64_t addr, void* data) {
#ifdef NASTIWIDGET_0
  mem->write(addr, (uint8_t*)data, -1, mem->get_word_size());
#endif
}
