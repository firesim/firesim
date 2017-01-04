#include "sim_mem.h"
#include <algorithm>

// TODO: support multi channels

sim_mem_t::sim_mem_t(simif_t* sim, int argc, char** argv): endpoint_t(sim) {
  std::vector<std::string> args(argv + 1, argv + argc);
  bool dramsim = false;
  uint64_t memsize = 1L << 26; // 64 KB
  const char* loadmem = NULL;
  latency = 10;
  for (auto &arg: args) {
    if (arg.find("+latency=") == 0) {
      latency = atoi(arg.c_str() + 9);
    }
    if (arg.find("+dramsim") == 0) {
      dramsim = true;
    }
    if (arg.find("+memsize=") == 0) {
      memsize = strtoll(arg.c_str() + 9, NULL, 10);
    }
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
  }
#ifdef NASTIWIDGET_0
  mem = dramsim ? (mm_t*) new mm_dramsim2_t : (mm_t*) new mm_magic_t;
  mem->init(memsize, MEM_DATA_BITS / 8, 64);
  if (loadmem) {
    fprintf(stderr, "[sw loadmem] %s\n", loadmem);
    void* mems[1];
    mems[0] = mem->get_data();
    ::load_mem(mems, loadmem, MEM_DATA_BITS / 8, 1);
  }
#endif // NASTIWIDGET_0
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
  
void sim_mem_t::init() {
#ifdef MidasMemModel
  write(MEMMODEL_0(readMaxReqs), 8);
  write(MEMMODEL_0(writeMaxReqs), 8);
  write(MEMMODEL_0(readLatency), latency);
  write(MEMMODEL_0(writeLatency), latency);
#endif // MidasMemModel
#ifdef SimpleLatencyPipe
  write(MEMMODEL_0(LATENCY), latency);
#endif // SimpleLatencyPipe
}

void sim_mem_t::recv(sim_mem_data_t& data) {
#ifdef NASTIWIDGET_0
  data.ar.valid = read(NASTIWIDGET_0(ar_valid));
  data.aw.valid = read(NASTIWIDGET_0(aw_valid));
  data.w.valid = read(NASTIWIDGET_0(w_valid));
  data.r.ready = read(NASTIWIDGET_0(r_ready));
  data.b.ready = read(NASTIWIDGET_0(b_ready));
  if (data.ar.fire()) {
    data.ar.addr = read(NASTIWIDGET_0(ar_addr));
    data.ar.id = read(NASTIWIDGET_0(ar_id));
    data.ar.size = read(NASTIWIDGET_0(ar_size));
    data.ar.len = read(NASTIWIDGET_0(ar_len));
  }
  if (data.aw.fire()) {
    data.aw.addr = read(NASTIWIDGET_0(aw_addr));
    data.aw.id = read(NASTIWIDGET_0(aw_id));
    data.aw.size = read(NASTIWIDGET_0(aw_size));
    data.aw.len = read(NASTIWIDGET_0(aw_len));
  }
  if (data.w.fire()) {
    data.w.strb = read(NASTIWIDGET_0(w_strb));
    data.w.last = read(NASTIWIDGET_0(w_last));
    for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
      data.w.data[i] = read(NASTIWIDGET_0(w_data[i]));
    }
  }
#endif
}

void sim_mem_t::send(sim_mem_data_t& data) {
#ifdef NASTIWIDGET_0
  if (data.b.fire()) {
    write(NASTIWIDGET_0(b_id),   data.b.id);
    write(NASTIWIDGET_0(b_resp), data.b.resp);
  }
  if (data.r.fire()) {
    write(NASTIWIDGET_0(r_id),   data.r.id);
    write(NASTIWIDGET_0(r_resp), data.r.resp);
    write(NASTIWIDGET_0(r_last), data.r.last);
    for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
      write(NASTIWIDGET_0(r_data[i]), data.r.data[i]);
    }
  }

  write(NASTIWIDGET_0(aw_ready), data.aw.ready);
  write(NASTIWIDGET_0(ar_ready), data.ar.ready);
  write(NASTIWIDGET_0(w_ready),  data.w.ready);
  write(NASTIWIDGET_0(b_valid),  data.b.valid);
  write(NASTIWIDGET_0(r_valid),  data.r.valid);
#endif
}

void sim_mem_t::tick() {
#ifdef NASTIWIDGET_0
  static size_t num_reads = 0;
  static size_t num_writes = 0;
  static sim_mem_data_t data;
  if (num_reads || num_writes || this->stall()) {
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

    if (num_reads || num_writes) this->delta(1);
    if (data.r.fire() && data.r.last) num_reads--;
    if (data.b.fire()) num_writes--;
  }
#endif // NASTIWIDGET_0
}
