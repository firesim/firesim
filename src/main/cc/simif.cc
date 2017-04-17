#include "simif.h"
#include <fstream>
#include <algorithm>

midas_time_t timestamp(){
#ifndef _WIN32
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
#else
  return clock();
#endif
}

double diff_secs(midas_time_t end, midas_time_t start) {
  return ((double)(end - start)) / TIME_DIV_CONST;
}

simif_t::simif_t() {
  pass = true;
  t = 0;
  fail_t = 0;
  seed = time(NULL);
  tracelen = 128; // by master widget
}

void simif_t::load_mem(std::string filename) {
#ifdef LOADMEM
  fprintf(stdout, "[loadmem] start loading\n");
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = MEM_DATA_BITS / 4;
  size_t addr = 0;
  std::string line;
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk ; j >= 0 ; j -= chunk) {
      biguint_t data = 0;
      for (size_t k = 0 ; k < chunk ; k++) {
        data |= biguint_t(parse_nibble(line[j+k])) << (4*(chunk-1-k));
      }
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  file.close();
  fprintf(stdout, "[loadmem] done\n");
#endif // LOADMEM
}

void simif_t::init(int argc, char** argv, bool log) {
  // Simulation reset
  write(MASTER(SIM_RESET), 1);
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
    }
    if (arg.find("+tracelen=") == 0) {
      tracelen = strtol(arg.c_str() + 10, NULL, 10);
    }
  }
  srand(seed);
  if (!fastloadmem && !loadmem.empty()) {
    load_mem(loadmem.c_str());
  }

#ifdef ENABLE_SNAPSHOT
  init_sampling(argc, argv);
#endif
}

void simif_t::target_reset(int pulse_start, int pulse_length) {
  poke(reset, 0);
  take_steps(pulse_start, true);
  poke(reset, 1);
  take_steps(pulse_length, true);
  poke(reset, 0);
#ifdef ENABLE_SNAPSHOT
  // flush I/O traces by target resets
  trace_count = std::min((size_t)(pulse_start + pulse_length), tracelen);
  read_traces(NULL);
  trace_count = 0;
#endif
}

int simif_t::finish() {
#ifdef ENABLE_SNAPSHOT
  finish_sampling();
#endif

  fprintf(stderr, "Runs %" PRIu64 " cycles\n", cycles());
  fprintf(stderr, "[%s] %s Test", pass ? "PASS" : "FAIL", TARGET_NAME);
  if (!pass) { fprintf(stdout, " at cycle %" PRIu64, fail_t); }
  fprintf(stderr, "\nSEED: %ld\n", seed);

  return pass ? EXIT_SUCCESS : EXIT_FAILURE;
}

static const size_t data_t_chunks = sizeof(data_t) / sizeof(uint32_t);

void simif_t::poke(size_t id, biguint_t& value) {
  if (log) fprintf(stderr, "* POKE %s.%s <- 0x%s *\n",
    TARGET_NAME, INPUT_NAMES[id], value.str().c_str());
  for (size_t i = 0 ; i < INPUT_CHUNKS[id] ; i++) {
    data_t data = 0;
    for (size_t j = 0 ; j < data_t_chunks ; j++) {
      size_t idx = i * data_t_chunks + j;
      if (idx < value.get_size())
        data |= ((data_t) value[idx]) << (32 * j);
    }
    write(INPUT_ADDRS[id]+i, data);
  }
}

void simif_t::peek(size_t id, biguint_t& value) {
  uint32_t buf[16] = {0};
  assert(OUTPUT_CHUNKS[id] <= 16);
  for (size_t i = 0 ; i < OUTPUT_CHUNKS[id] ; i++) {
    data_t data = read(OUTPUT_ADDRS[id] + i);
    for (size_t j = 0 ; j < data_t_chunks ; j++) {
      size_t idx = i * data_t_chunks + j;
      buf[idx] = data >> (32 * j);
    }
  }
  value = biguint_t(buf, OUTPUT_CHUNKS[id] * data_t_chunks);
  if (log) fprintf(stderr, "* PEEK %s.%s -> 0x%s *\n",
    TARGET_NAME, OUTPUT_NAMES[id], value.str().c_str());
}

bool simif_t::expect(size_t id, biguint_t& expected) {
  biguint_t value;
  peek(id, value);
  bool pass = value == expected;
  if (log) fprintf(stderr, "* EXPECT %s.%s -> 0x%s ?= 0x%s : %s\n",
    TARGET_NAME, OUTPUT_NAMES[id], value.str().c_str(), expected.str().c_str(),
    pass ? "PASS" : "FAIL");
  return expect(pass, NULL);
}

void simif_t::read_mem(size_t addr, biguint_t& value) {
#ifdef LOADMEM
  write(LOADMEM_R_ADDRESS, addr);
  uint32_t buf[MEM_DATA_CHUNK * data_t_chunks];
  for (size_t i = 0 ; i < MEM_DATA_CHUNK; i++) {
    data_t data = read(LOADMEM_R_DATA);
    for (size_t j = 0 ; j < data_t_chunks ; j++) {
      size_t idx = i * data_t_chunks + j;
      buf[idx] = data >> 32 * j;
    }
  }
  value = biguint_t(buf, MEM_DATA_CHUNK * data_t_chunks);
#endif
}

void simif_t::write_mem(size_t addr, biguint_t& value) {
#ifdef LOADMEM
  write(LOADMEM_W_ADDRESS, addr);
  for (size_t i = 0; i < MEM_DATA_CHUNK; i++) {
    data_t data = 0;
    for (size_t j = 0 ; j < data_t_chunks ; j++) {
      size_t idx = i * data_t_chunks + j;
      if (idx < value.get_size())
        data |= ((data_t) value[idx]) << (32 * j);
    }
    write(LOADMEM_W_DATA, data);
  }
#endif
}

void simif_t::step(int n, bool blocking) {
  if (n == 0) return;
  assert(n > 0);
#ifdef ENABLE_SNAPSHOT
  reservoir_sampling(n);
#endif
  // take steps
  if (log) fprintf(stderr, "* STEP %d -> %" PRIu64 " *\n", n, (t + n));
  take_steps(n, blocking);
  t += n;
}
