#include "sim_mem.h"

sim_mem_t::sim_mem_t(simif_t* s, int argc, char** argv): sim(s) {
  std::vector<std::string> args(argv + 1, argv + argc);
  bool dramsim = false;
  uint64_t memsize = 1L << 32;
  const char* loadmem = NULL;
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
  
void sim_mem_t::init() {
#ifdef MidasMemModel
  sim->write(MEMMODEL_0(readMaxReqs), 8);
  sim->write(MEMMODEL_0(writeMaxReqs), 8);
  sim->write(MEMMODEL_0(readLatency), latency);
  sim->write(MEMMODEL_0(writeLatency), latency);
#endif // MidasMemModel
#ifdef SimpleLatencyPipe
  sim->write(MEMMODEL_0(LATENCY), latency);
#endif // SimpleLatencyPipe
}

void sim_mem_t::tick() {
  static const size_t MEM_CHUNKS = MEM_DATA_BITS / (8 * sizeof(uint32_t));
#ifdef NASTIWIDGET_0
  if (sim->read(NASTIWIDGET_0(stall))) {
    size_t steps = 0;
    do {
      uint32_t w_data_buf[16];
      for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
        w_data_buf[i] = sim->read(NASTIWIDGET_0(w_data[i]));
      }
      biguint_t w_data(w_data_buf, MEM_CHUNKS);

      bool ar_ready = mem->ar_ready();
      bool aw_ready = mem->aw_ready();
      bool w_ready = mem->w_ready();

      mem->tick(
        false,
        sim->read(NASTIWIDGET_0(ar_valid)),
        sim->read(NASTIWIDGET_0(ar_addr)),
        sim->read(NASTIWIDGET_0(ar_id)),
        sim->read(NASTIWIDGET_0(ar_size)),
        sim->read(NASTIWIDGET_0(ar_len)),

        sim->read(NASTIWIDGET_0(aw_valid)),
        sim->read(NASTIWIDGET_0(aw_addr)),
        sim->read(NASTIWIDGET_0(aw_id)),
        sim->read(NASTIWIDGET_0(aw_size)),
        sim->read(NASTIWIDGET_0(aw_len)),

        sim->read(NASTIWIDGET_0(w_valid)),
        sim->read(NASTIWIDGET_0(w_strb)),
        w_data.get_data(),
        sim->read(NASTIWIDGET_0(w_last)),

        sim->read(NASTIWIDGET_0(r_ready)),
        sim->read(NASTIWIDGET_0(b_ready))
      );

      sim->write(NASTIWIDGET_0(aw_ready), aw_ready);
      sim->write(NASTIWIDGET_0(ar_ready), ar_ready);
      sim->write(NASTIWIDGET_0(w_ready),  w_ready);

      sim->write(NASTIWIDGET_0(b_id),     mem->b_id());
      sim->write(NASTIWIDGET_0(b_resp),   mem->b_resp());
      sim->write(NASTIWIDGET_0(b_valid),  mem->b_valid());

      sim->write(NASTIWIDGET_0(r_id),     mem->r_id());
      sim->write(NASTIWIDGET_0(r_resp),   mem->r_resp());
      sim->write(NASTIWIDGET_0(r_last),   mem->r_last());
      biguint_t r_data((const uint32_t*)  mem->r_data(), MEM_CHUNKS);
      for (size_t i = 0 ; i < MEM_CHUNKS ; i++) {
        sim->write(NASTIWIDGET_0(r_data[i]), r_data[i]);
      }
      sim->write(NASTIWIDGET_0(r_valid),  mem->r_valid());

      steps++;
    } while(!(mem->r_valid() && mem->r_last()) && !mem->b_valid());

    sim->write(NASTIWIDGET_0(steps), steps);
  }
#endif // NASTIWIDGET_0
}
