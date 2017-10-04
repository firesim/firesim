#include "rocketchip.h"
#include "endpoints/serial.h"
#include "endpoints/uart.h"
#include "endpoints/fpga_model.h"
#include "endpoints/sim_mem.h"
#include "endpoints/fpga_memory_model.h"

rocketchip_t::rocketchip_t(int argc, char** argv, fesvr_proxy_t* fesvr): fesvr(fesvr)
{
  std::vector<std::string> args(argv + 1, argv + argc);
  max_cycles = -1;
  for (auto &arg: args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str()+12);
    }
    //TODO: Specify this in cycles, not iterations of inner loop
    if (arg.find("+profile-interval=") == 0) {
      profile_interval = atoi(arg.c_str()+18);
    }
  }

  add_endpoint(new uart_t(this));
  add_endpoint(new serial_t(this, fesvr));

#ifdef NASTIWIDGET_0
  endpoints.push_back(new sim_mem_t(this, argc, argv));
#endif

#ifdef MEMMODEL_0
  fpga_models.push_back(new FpgaMemoryModel(
      this,
      // Casts are required for now since the emitted type can change...
      AddressMap(MEMMODEL_0_R_num_registers,
                 (const unsigned int*) MEMMODEL_0_R_addrs,
                 (const char* const*) MEMMODEL_0_R_names,
                 MEMMODEL_0_W_num_registers,
                 (const unsigned int*) MEMMODEL_0_W_addrs,
                 (const char* const*) MEMMODEL_0_W_names),
      argc, argv, "memory_stats.csv"));
#endif

}

void rocketchip_t::loadmem() {
  fesvr_loadmem_t loadmem; 
  while (fesvr->recv_loadmem_req(loadmem)) {
    assert(loadmem.size <= 1024);
    static char buf[1024]; // This should be enough...
    fesvr->recv_loadmem_data(buf, loadmem.size);
#ifdef LOADMEM
    const size_t mem_data_bytes = MEM_DATA_CHUNK * sizeof(data_t);
#define WRITE_MEM(addr, src) \
    mpz_t data; \
    mpz_init(data); \
    mpz_import(data, mem_data_bytes / sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, src); \
    write_mem(addr, data); \
    mpz_clear(data);
#else
    const size_t mem_data_bytes = MEM_DATA_BITS / 8;
#define WRITE_MEM(addr, src) \
    for (auto e: endpoints) { \
      if (sim_mem_t* s = dynamic_cast<sim_mem_t*>(e)) { \
        s->write_mem(addr, src); \
      } \
    }
#endif
    for (size_t off = 0 ; off < loadmem.size ; off += mem_data_bytes) {
      WRITE_MEM(loadmem.addr + off, buf + off);
    }
  }
}

#ifndef ENABLE_SNAPSHOT
#define GET_DELTA step_size
#else
#define GET_DELTA std::min(step_size, get_tracelen())
#endif

void rocketchip_t::loop(size_t step_size) {
  size_t delta = GET_DELTA;
  size_t delta_sum = 0;
  size_t profile_count = 0;

  do {
    if (fesvr->busy()) {
      step(1, false);
      delta_sum += 1;
      if (--delta == 0) delta = GET_DELTA;
    } else {
      step(delta, false);
      delta_sum += delta;
      delta = GET_DELTA;
    }

    bool _done;
    do {
      _done = done();
      for (auto e: endpoints) {
        _done &= e->done();
        e->tick();
      }
    } while(!_done);

    if (delta_sum == step_size || fesvr->busy()) {
      for (auto e: endpoints) {
        if (serial_t* s = dynamic_cast<serial_t*>(e)) {
          s->work();
        }
      }
      loadmem();

      // Every profile_interval iterations, collect state from all fpga models
      profile_count++;
      if (profile_interval != 0 && profile_count == profile_interval) {
        for (auto mod: fpga_models) {
          mod->profile();
        }
        profile_count = 0;
      }

      if (delta_sum == step_size) delta_sum = 0;
    }
  } while (!fesvr->done() && cycles() <= max_cycles);
}

void rocketchip_t::run(size_t step_size) {
  for (auto e: fpga_models) {
    e->init();
  }

  // Assert reset T=0 -> 5
  target_reset(0, 5);

  uint64_t start_time = timestamp();
  loop(step_size);

  uint64_t end_time = timestamp();
  double sim_time = diff_secs(end_time, start_time);
  double sim_speed = ((double) cycles()) / (sim_time * 1000.0);
  if (sim_speed > 1000.0) {
    fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f MHz\n", sim_time, sim_speed / 1000.0);
  } else {
    fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f KHz\n", sim_time, sim_speed);
  }
  int exitcode = fesvr->exit_code();
  if (exitcode) {
    fprintf(stderr, "*** FAILED *** (code = %d) after %llu cycles\n", exitcode, cycles());
  } else if (cycles() > max_cycles) {
    fprintf(stderr, "*** FAILED *** (timeout) after %llu cycles\n", cycles());
  } else {
    fprintf(stderr, "*** PASSED *** after %llu cycles\n", cycles());
  }
  expect(!exitcode, NULL);

  for (auto e: fpga_models) {
    e->finish();
  }
}
