// See LICENSE for license details.

#include "simif.h"
#include <algorithm>
#include <fstream>
#include <inttypes.h>

midas_time_t timestamp() {
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

double diff_secs(midas_time_t end, midas_time_t start) {
  return ((double)(end - start)) / TIME_DIV_CONST;
}

simif_t::simif_t(): token_hashers(new simif_token_hashers_t(this)) {
  seed = time(NULL); // FIXME: better initail seed?
  SIMULATIONMASTER_0_substruct_create;
  this->master_mmio_addrs = SIMULATIONMASTER_0_substruct;
  LOADMEMWIDGET_0_substruct_create;
  this->loadmem_mmio_addrs = LOADMEMWIDGET_0_substruct;
  CLOCKBRIDGEMODULE_0_substruct_create;
  this->clock_bridge_mmio_addrs = CLOCKBRIDGEMODULE_0_substruct;
}

void simif_t::init(int argc, char **argv) {
  // Do any post-constructor initialization required before requesting MMIO
  this->host_init(argc, argv);
  while (!read(this->master_mmio_addrs->INIT_DONE))
    ;
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string loadmem;
  bool fastloadmem = false;
  for (auto &arg : args) {
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str() + 9;
    }
    if (arg.find("+seed=") == 0) {
      seed = strtoll(arg.c_str() + 6, NULL, 10);
      fprintf(stderr, "Using custom SEED: %ld\n", seed);
    }
  }
  gen.seed(seed);
  fprintf(stderr,
          "random min: 0x%" PRIx64 ", random max: 0x%" PRIx64 "\n",
          gen.min(),
          gen.max());
  if (!fastloadmem && !loadmem.empty()) {
    load_mem(loadmem.c_str());
  }
  // This can be called again if a later start time is desired
  record_start_times();
}

uint64_t simif_t::actual_tcycle() {
  write(this->clock_bridge_mmio_addrs->tCycle_latch, 1);
  uint32_t cycle_l = read(this->clock_bridge_mmio_addrs->tCycle_0);
  uint32_t cycle_h = read(this->clock_bridge_mmio_addrs->tCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}

uint64_t simif_t::hcycle() {
  write(this->clock_bridge_mmio_addrs->hCycle_latch, 1);
  uint32_t cycle_l = read(this->clock_bridge_mmio_addrs->hCycle_0);
  uint32_t cycle_h = read(this->clock_bridge_mmio_addrs->hCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}

void simif_t::load_mem(std::string filename) {
  fprintf(stdout, "[loadmem] start loading\n");
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = MEM_DATA_BITS / 4;
  size_t addr = 0;
  std::string line;
  mpz_t data;
  mpz_init(data);
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk; j >= 0; j -= chunk) {
      mpz_set_str(data, line.substr(j, chunk).c_str(), 16);
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  mpz_clear(data);
  file.close();
  fprintf(stdout, "[loadmem] done\n");
}

// NB: mpz_t variables may not export <size> <uint32_t> beats, if initialized
// with an array of zeros.
void simif_t::read_mem(size_t addr, mpz_t &value) {
  write(this->loadmem_mmio_addrs->R_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->R_ADDRESS_L, addr & ((1ULL << 32) - 1));
  const size_t size = MEM_DATA_CHUNK;
  uint32_t data[size];
  for (size_t i = 0; i < size; i++) {
    data[i] = read(this->loadmem_mmio_addrs->R_DATA);
  }
  mpz_import(value, size, -1, sizeof(uint32_t), 0, 0, data);
}

void simif_t::write_mem(size_t addr, mpz_t &value) {
  write(this->loadmem_mmio_addrs->W_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  write(this->loadmem_mmio_addrs->W_LENGTH, 1);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < MEM_DATA_CHUNK; i++) {
    write(this->loadmem_mmio_addrs->W_DATA, i < size ? data[i] : 0);
  }
}

#define MEM_DATA_CHUNK_BYTES (MEM_DATA_CHUNK * sizeof(uint32_t))
#define ceil_div(a, b) (((a)-1) / (b) + 1)

void simif_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  write(this->loadmem_mmio_addrs->W_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  size_t num_beats = ceil_div(bytes, MEM_DATA_CHUNK_BYTES);
  write(this->loadmem_mmio_addrs->W_LENGTH, num_beats);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < num_beats * MEM_DATA_CHUNK; i++) {
    write(this->loadmem_mmio_addrs->W_DATA, i < size ? data[i] : 0);
  }
}

void simif_t::zero_out_dram() {
  write(this->loadmem_mmio_addrs->ZERO_OUT_DRAM, 1);
  while (!read(this->loadmem_mmio_addrs->ZERO_FINISHED))
    ;
}

void simif_t::record_start_times() {
  this->start_hcycle = hcycle();
  this->start_time = timestamp();
}

void simif_t::record_end_times() {
  this->end_time = timestamp();
  this->end_tcycle = actual_tcycle();
  this->end_hcycle = hcycle();
}
void simif_t::print_simulation_performance_summary() {
  // Must call record_start_times and record_end_times before invoking this
  // function
  assert(start_hcycle != -1);
  assert(end_hcycle != 0);
  uint64_t hcycles = end_hcycle - start_hcycle;
  double sim_time = diff_secs(end_time, start_time);
  double sim_speed = ((double)end_tcycle) / (sim_time * 1000.0);
  double measured_host_frequency = ((double)hcycles) / (sim_time * 1000.0);
  double fmr = ((double)hcycles / end_tcycle);

  fprintf(stderr, "\nEmulation Performance Summary\n");
  fprintf(stderr, "------------------------------\n");
  fprintf(stderr, "Wallclock Time Elapsed: %.1f s\n", sim_time);
  // Provide enough sig-figs to let the report be useful in RTL sim
  fprintf(stderr, "Host Frequency: ");
  if (measured_host_frequency > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", measured_host_frequency / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", measured_host_frequency);
  }

  fprintf(stderr, "Target Cycles Emulated: %" PRIu64 "\n", end_tcycle);
  fprintf(stderr, "Effective Target Frequency: ");
  if (sim_speed > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", sim_speed / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", sim_speed);
  }
  fprintf(stderr, "FMR: %.2f\n", fmr);
  fprintf(stderr,
          "Note: The latter three figures are based on the fastest "
          "target clock.\n");
}
