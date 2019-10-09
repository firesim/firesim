// See LICENSE for license details.

#include "simif.h"
#include <fstream>
#include <algorithm>

midas_time_t timestamp(){
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

double diff_secs(midas_time_t end, midas_time_t start) {
  return ((double)(end - start)) / TIME_DIV_CONST;
}

simif_t::simif_t() {
  pass = true;
  t = 0;
  fail_t = 0;
  seed = time(NULL); // FIXME: better initail seed?
  SIMULATIONMASTER_0_substruct_create;
  this->master_mmio_addrs = SIMULATIONMASTER_0_substruct;
  LOADMEMWIDGET_0_substruct_create;
  this->loadmem_mmio_addrs = LOADMEMWIDGET_0_substruct;
  PEEKPOKEBRIDGEMODULE_0_substruct_create;
  this->defaultiowidget_mmio_addrs = PEEKPOKEBRIDGEMODULE_0_substruct;
}

void simif_t::init(int argc, char** argv, bool log) {
  // Simulation reset
  write(this->master_mmio_addrs->SIM_RESET, 1);
  while(!done());

  this->log = log;
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string loadmem;
  bool fastloadmem = false;
  for (auto &arg: args) {
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
  fprintf(stderr, "random min: 0x%llx, random max: 0x%llx\n", gen.min(), gen.max());
  if (!fastloadmem && !loadmem.empty()) {
    load_mem(loadmem.c_str());
  }

#ifdef ENABLE_SNAPSHOT
  init_sampling(argc, argv);
#endif
}

uint64_t simif_t::actual_tcycle() {
    write(this->defaultiowidget_mmio_addrs->tCycle_latch, 1);
    data_t cycle_l = read(this->defaultiowidget_mmio_addrs->tCycle_0);
    data_t cycle_h = read(this->defaultiowidget_mmio_addrs->tCycle_1);
    return (((uint64_t) cycle_h) << 32) | cycle_l;
}

uint64_t simif_t::hcycle() {
    write(this->defaultiowidget_mmio_addrs->hCycle_latch, 1);
    data_t cycle_l = read(this->defaultiowidget_mmio_addrs->hCycle_0);
    data_t cycle_h = read(this->defaultiowidget_mmio_addrs->hCycle_1);
    return (((uint64_t) cycle_h) << 32) | cycle_l;
}

void simif_t::target_reset(int pulse_length) {
  poke(reset, 1);
  take_steps(pulse_length, true);
  poke(reset, 0);
#ifdef ENABLE_SNAPSHOT
  // flush I/O traces by target resets
  trace_count = std::min((size_t)(pulse_length), tracelen);
  read_traces(NULL);
  trace_count = 0;
#endif
}

int simif_t::finish() {
#ifdef ENABLE_SNAPSHOT
  finish_sampling();
#endif

  fprintf(stderr, "Runs %llu cycles\n", actual_tcycle());
  fprintf(stderr, "[%s] %s Test", pass ? "PASS" : "FAIL", TARGET_NAME);
  if (!pass) { fprintf(stdout, " at cycle %llu", fail_t); }
  fprintf(stderr, "\nSEED: %ld\n", seed);

  return pass ? EXIT_SUCCESS : EXIT_FAILURE;
}

static const size_t data_t_chunks = sizeof(data_t) / sizeof(uint32_t);

void simif_t::poke(size_t id, mpz_t& value) {
  if (log) {
    char* v_str = mpz_get_str(NULL, 16, value);
    fprintf(stderr, "* POKE %s.%s <- 0x%s *\n", TARGET_NAME, INPUT_NAMES[id], v_str);
    free(v_str);
  }
  size_t size;
  data_t* data = (data_t*)mpz_export(NULL, &size, -1, sizeof(data_t), 0, 0, value);
  for (size_t i = 0 ; i < INPUT_CHUNKS[id] ; i++) {
    write(INPUT_ADDRS[id]+i, i < size ? data[i] : 0);
  }
}

void simif_t::peek(size_t id, mpz_t& value) {
  const size_t size = (const size_t)OUTPUT_CHUNKS[id];
  data_t data[size];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = read((size_t)OUTPUT_ADDRS[id]+i);
  }
  mpz_import(value, size, -1, sizeof(data_t), 0, 0, data);
  if (log) {
    char* v_str = mpz_get_str(NULL, 16, value);
    fprintf(stderr, "* PEEK %s.%s -> 0x%s *\n", TARGET_NAME, (const char*)OUTPUT_NAMES[id], v_str);
    free(v_str);
  }
}

bool simif_t::expect(size_t id, mpz_t& expected) {
  mpz_t value;
  mpz_init(value);
  peek(id, value);
  bool pass = mpz_cmp(value, expected) == 0;
  if (log) {
    char* v_str = mpz_get_str(NULL, 16, value);
    char* e_str = mpz_get_str(NULL, 16, expected);
    fprintf(stderr, "* EXPECT %s.%s -> 0x%s ?= 0x%s : %s\n",
      TARGET_NAME, (const char*)OUTPUT_NAMES[id], v_str, e_str, pass ? "PASS" : "FAIL");
    free(v_str);
    free(e_str);
  }
  mpz_clear(value);
  return expect(pass, NULL);
}

void simif_t::step(uint32_t n, bool blocking) {
  if (n == 0) return;
#ifdef ENABLE_SNAPSHOT
  reservoir_sampling(n);
#endif
  // take steps
  if (log) fprintf(stderr, "* STEP %d -> %llu *\n", n, (t + n));
  take_steps(n, blocking);
  t += n;
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
    for (int j = line.length() - chunk ; j >= 0 ; j -= chunk) {
      mpz_set_str(data, line.substr(j, chunk).c_str(), 16);
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  mpz_clear(data);
  file.close();
  fprintf(stdout, "[loadmem] done\n");
}

// NB: mpz_t variables may not export <size> <data_t> beats, if initialized with an array of zeros.
void simif_t::read_mem(size_t addr, mpz_t& value) {
  write(this->loadmem_mmio_addrs->R_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->R_ADDRESS_L, addr & ((1ULL << 32) - 1));
  const size_t size = MEM_DATA_CHUNK;
  data_t data[size];
  for (size_t i = 0 ; i < size ; i++) {
    data[i] = read(this->loadmem_mmio_addrs->R_DATA);
  }
  mpz_import(value, size, -1, sizeof(data_t), 0, 0, data);
}

void simif_t::write_mem(size_t addr, mpz_t& value) {
  write(this->loadmem_mmio_addrs->W_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  write(this->loadmem_mmio_addrs->W_LENGTH, 1);
  size_t size;
  data_t* data = (data_t*)mpz_export(NULL, &size, -1, sizeof(data_t), 0, 0, value);
  for (size_t i = 0 ; i < MEM_DATA_CHUNK ; i++) {
    write(this->loadmem_mmio_addrs->W_DATA, i < size ? data[i] : 0);
  }
}

#define MEM_DATA_CHUNK_BYTES (MEM_DATA_CHUNK*sizeof(data_t))
#define ceil_div(a, b) (((a) - 1) / (b) + 1)

void simif_t::write_mem_chunk(size_t addr, mpz_t& value, size_t bytes) {
  write(this->loadmem_mmio_addrs->W_ADDRESS_H, addr >> 32);
  write(this->loadmem_mmio_addrs->W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  size_t num_beats = ceil_div(bytes, MEM_DATA_CHUNK_BYTES);
  write(this->loadmem_mmio_addrs->W_LENGTH, num_beats);
  size_t size;
  data_t* data = (data_t*)mpz_export(NULL, &size, -1, sizeof(data_t), 0, 0, value);
  for (size_t i = 0 ; i < num_beats * MEM_DATA_CHUNK ; i++) {
    write(this->loadmem_mmio_addrs->W_DATA, i < size ? data[i] : 0);
  }
}

void simif_t::zero_out_dram() {
  write(this->loadmem_mmio_addrs->ZERO_OUT_DRAM, 1);
  while(!read(this->loadmem_mmio_addrs->ZERO_FINISHED));
}
