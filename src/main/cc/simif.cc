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

#ifdef ENABLE_SNAPSHOT
void simif_t::init_sampling(int argc, char** argv) {
  // Read mapping files
  sample_t::init_chains(std::string(TARGET_NAME) + ".chain");

  // Init sample variables
  sample_file = std::string(TARGET_NAME) + ".sample";
  sample_num = 30;
  last_sample = NULL;
  last_sample_id = 0;
  profile = false;
  sample_count = 0;
  sample_time = 0;
  trace_count = 0;

  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if (arg.find("+sample=") == 0) {
      sample_file = arg.c_str() + 8;
    }
    if (arg.find("+samplenum=") == 0) {
      sample_num = strtol(arg.c_str() + 11, NULL, 10);
    }
    if (arg.find("+profile") == 0) {
      profile = true;
    }
  }

  samples = new sample_t*[sample_num];
  for (size_t i = 0 ; i < sample_num ; i++) samples[i] = NULL;

  // flush output traces by sim reset
  for (size_t k = 0 ; k < OUT_TR_SIZE ; k++) {
    size_t addr = OUT_TR_ADDRS[k];
    size_t chunk = OUT_TR_CHUNKS[k];
    for (size_t off = 0 ; off < chunk ; off++)
      read(addr+off);
  }
  for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
    read((size_t)OUT_TR_READY_ADDRS[id]);
    bits_id = !read((size_t)OUT_TR_VALID_ADDRS[id]) ?
      bits_id + (size_t)OUT_TR_BITS_FIELD_NUMS[id] :
      trace_ready_valid_bits(
        NULL,
        false,
        bits_id,
        (size_t)OUT_TR_BITS_ADDRS[id],
        (size_t)OUT_TR_BITS_CHUNKS[id],
        (size_t)OUT_TR_BITS_FIELD_NUMS[id]);
  }

  if (profile) sim_start_time = timestamp();
}

void simif_t::finish_sampling() {
  // tail samples
  if (last_sample != NULL) {
    if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
    samples[last_sample_id] = read_traces(last_sample);
  }

  // dump samples
#if DAISY_WIDTH > 32
  std::ofstream file(sample_file.c_str());
#else
  FILE *file = fopen(sample_file.c_str(), "w");
#endif
  sample_t::dump_chains(file);
  for (size_t i = 0 ; i < sample_num ; i++) {
    if (samples[i] != NULL) {
      samples[i]->dump(file);
      delete samples[i];
    }
  }

  if (profile) {
    double sim_time = diff_secs(timestamp(), sim_start_time);
    fprintf(stderr, "Simulation Time: %.3f s, Sample Time: %.3f s, Sample Count: %zu\n",
                    sim_time, diff_secs(sample_time, 0), sample_count);
  }
}

size_t simif_t::trace_ready_valid_bits(
    sample_t* sample,
    bool poke,
    size_t bits_id,
    size_t bits_addr,
    size_t bits_chunk,
    size_t num_fields) {
  data_t *bits_data = new data_t[bits_chunk];
  for (size_t off = 0 ; off < bits_chunk ; off++) {
    bits_data[off] = read(bits_addr + off);
  }
  if (sample) {
    biguint_t data((uint32_t*)bits_data, bits_chunk * data_t_chunks);
    for (size_t k = 0, off = 0 ; k < num_fields ; k++, bits_id++) {
      size_t field_width = ((unsigned int*)(
        poke ? IN_TR_BITS_FIELD_WIDTHS : OUT_TR_BITS_FIELD_WIDTHS))[bits_id];
      size_t field_chunk = ((field_width - 1) / DAISY_WIDTH) + 1;
      biguint_t value = data >> off;
#if DAISY_WIDTH > 32
      data_t* field_data = new data_t[field_chunk](); // zero-out
      for (size_t i = 0 ; i < field_chunk ; i++) {
        for (size_t j = 0 ; j < data_t_chunks ; j++) {
          field_data[i] |= ((data_t)value[i * data_t_chunks + j]) << 32 * j;
        }
      }
#else
      data_t* field_data = new data_t[field_chunk];
      std::copy(value.get_data(), value.get_data() + field_chunk, field_data);
#endif
      data_t mask = (1L << (field_width % DAISY_WIDTH)) - 1;
      if (mask) {
        field_data[field_chunk-1] = field_data[field_chunk-1] & mask;
      }
      sample->add_cmd(poke ?
        (sample_inst_t*) new poke_t(IN_TR_BITS, bits_id, field_data, field_chunk):
        (sample_inst_t*) new expect_t(OUT_TR_BITS, bits_id, field_data, field_chunk));
      off += field_width;
    }
  }

  delete[] bits_data;
  return bits_id;
}

sample_t* simif_t::read_traces(sample_t *sample) {
  for (size_t i = 0 ; i < trace_count ; i++) {
    // wire input traces from FPGA
    for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
      size_t addr = IN_TR_ADDRS[id];
      size_t chunk = IN_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(addr+off);
      }
      if (sample) sample->add_cmd(new poke_t(IN_TR, id, data, chunk));
    }

    // ready valid input traces from FPGA
    for (size_t id = 0, bits_id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)IN_TR_VALID_ADDRS[id];
      data_t* valid_data = new data_t[1];
      valid_data[0] = read(valid_addr);
      if (sample) sample->add_cmd(new poke_t(IN_TR_VALID, id, valid_data, 1));
      bits_id = !valid_data[0] ?
        bits_id + (size_t)IN_TR_BITS_FIELD_NUMS[id] :
        trace_ready_valid_bits(
          sample,
          true,
          bits_id,
          (size_t)IN_TR_BITS_ADDRS[id],
          (size_t)IN_TR_BITS_CHUNKS[id],
          (size_t)IN_TR_BITS_FIELD_NUMS[id]);
    }
    for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)OUT_TR_READY_ADDRS[id];
      data_t* ready_data = new data_t[1];
      ready_data[0] = read(ready_addr);
      if (sample) sample->add_cmd(new poke_t(OUT_TR_READY, id, ready_data, 1));
    }

    if (sample) sample->add_cmd(new step_t(1));

    // wire output traces from FPGA
    for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
      size_t addr = OUT_TR_ADDRS[id];
      size_t chunk = OUT_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = read(addr+off);
      }
      if (sample && i > 0) sample->add_cmd(new expect_t(OUT_TR, id, data, chunk));
    }

    // ready valid output traces from FPGA
    for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)OUT_TR_VALID_ADDRS[id];
      data_t* valid_data = new data_t[1];
      valid_data[0] = read(valid_addr);
      if (sample) sample->add_cmd(new expect_t(OUT_TR_VALID, id, valid_data, 1));
      bits_id = !valid_data[0] ?
        bits_id + (size_t)OUT_TR_BITS_FIELD_NUMS[id] :
        trace_ready_valid_bits(
          sample,
          false,
          bits_id,
          (size_t)OUT_TR_BITS_ADDRS[id],
          (size_t)OUT_TR_BITS_CHUNKS[id],
          (size_t)OUT_TR_BITS_FIELD_NUMS[id]);
    }
    for (size_t id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)IN_TR_READY_ADDRS[id];
      data_t* ready_data = new data_t[1];
      ready_data[0] = read(ready_addr);
      if (sample) sample->add_cmd(new expect_t(IN_TR_READY, id, ready_data, 1));
    }
  }

  return sample;
}

static inline char* int_to_bin(char *bin, data_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
  return bin;
}

sample_t* simif_t::read_snapshot() {
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1];
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    const size_t chain_loop = sample_t::get_chain_loop(type);
    const size_t chain_len = sample_t::get_chain_len(type);
    for (size_t k = 0 ; k < chain_loop ; k++) {
      for (size_t i = 0 ; i < CHAIN_SIZE[t] ; i++) {
        if (type == SRAM_CHAIN) write(SRAM_RESTART_ADDR + i, 1);
        for (size_t j = 0 ; j < chain_len ; j++) {
          snap << int_to_bin(bin, read(CHAIN_ADDR[t] + i), DAISY_WIDTH);
        }
      }
    }
  }
  return new sample_t(snap.str().c_str(), cycles());
}

void simif_t::reservoir_sampling(size_t n) {
  if (t % tracelen == 0) {
    midas_time_t start_time = 0;
    size_t record_id = t / tracelen;
    size_t sample_id = record_id < sample_num ? record_id : rand() % (record_id + 1);
    if (sample_id < sample_num) {
      sample_count++;
      if (profile) start_time = timestamp();
      if (last_sample != NULL) {
        if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
        samples[last_sample_id] = read_traces(last_sample);
      }
      last_sample = read_snapshot();
      last_sample_id = sample_id;
      trace_count = 0;
      if (profile) sample_time += (timestamp() - start_time);
    }
  }
  if (trace_count < tracelen) trace_count += n;
}
#endif
