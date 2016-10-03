#include "simif.h"
#include <fstream>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log): prefix(_prefix), log(_log) 
{
  ok = true;
  t = 0;
  fail_t = 0;
  trace_count = 0;
  trace_len = TRACE_MAX_LEN;

  last_sample = NULL;
  last_sample_id = 0;

  profile = false;
  sample_num = 30; // SAMPLE_NUM;
  sample_count = 0;
  sample_time = 0;

  seed = time(NULL);
  srand(seed);

  size_t i;
  for (i = 0 ; i < args.size() ; i++) {
    if (args[i].length() && args[i][0] != '-' && args[i][0] != '+')
      break;
  }
  hargs.insert(hargs.begin(), args.begin(), args.begin() + i);
  targs.insert(targs.begin(), args.begin() + i, args.end());

  // Read mapping files
  read_map(prefix+".map");
#if ENABLE_SNAPSHOT
  sample_t::init_chains(prefix+".chain");
#endif
}

simif_t::~simif_t() { 
  fprintf(stdout, "[%s] %s Test", ok ? "PASS" : "FAIL", prefix.c_str());
  if (!ok) { fprintf(stdout, " at cycle %llu", (long long) fail_t); }
  fprintf(stdout, "\nSEED: %ld\n", seed);
}

void simif_t::read_map(std::string filename) {
  enum MAP_TYPE { IO_IN, IO_OUT, IN_TR, OUT_TR };
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while (getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t type, id, chunk;
      iss >> type >> path >> id >> chunk;
      switch (static_cast<MAP_TYPE>(type)) {
        case IO_IN:
          in_map[path] = id;
          in_chunks[id] = chunk;
          break;
        case IO_OUT:
          out_map[path] = id;
          out_chunks[id] = chunk;
          break;
        case IN_TR:
          in_tr_map[path] = id;
          out_chunks[id] = chunk;
          break;
        case OUT_TR:
          out_tr_map[path] = id;
          out_chunks[id] = chunk;
          break;
        default:
          break;
      }
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::load_mem(std::string filename) {
  std::ifstream file(filename.c_str());
  if (file) {
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
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  } 
  file.close();
}

void simif_t::init() {
  for (size_t k = 0 ; k < 5 ; k++) {
    poke_channel(HOST_RESET_ADDR, 0);
    poke_channel(SIM_RESET_ADDR, 0);
    while(!peek_channel(DONE_ADDR));
    for (size_t i = 0 ; i < PEEK_SIZE ; i++) {
      peek_map[i] = peek_channel(CTRL_NUM + i);
    }
#if ENABLE_SNAPSHOT
    // flush traces from initialization
    trace_count = 1;
    read_traces(NULL);
    trace_count = 0;
#endif
  }

  for (auto &arg: hargs) {
    if (arg.find("+loadmem=") == 0) {
      std::string filename = arg.c_str()+9;
      fprintf(stdout, "[loadmem] start loading\n");
      load_mem(filename);
      fprintf(stdout, "[loadmem] done\n");
    }
    if (arg.find("+samplenum=") == 0) {
      sample_num = strtol(arg.c_str()+11, NULL, 10);
    }
    if (arg.find("+profile") == 0) profile = true;
  }

#if ENABLE_SAMPLE
  samples = new sample_t*[sample_num];
  for (size_t i = 0 ; i < sample_num ; i++) {
     samples[i] = NULL;
  }
#endif
  if (profile) sim_start_time = timestamp();
}

void simif_t::finish() {
#if ENABLE_SNAPSHOT
  // tail samples
  if (last_sample != NULL) {
    if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
    samples[last_sample_id] = read_traces(last_sample);
  }
#endif

  if (profile) {
    double sim_time = (double) (timestamp() - sim_start_time) / 1000000.0;
    fprintf(stdout, "Simulation Time: %.3f s, Sample Time: %.3f s, Sample Count: %d\n", 
                    sim_time, (double) sample_time / 1000000.0, sample_count);
  }

#if ENALBE_SNAPSHOT
  // dump samples
  std::string filename = prefix + ".sample";
  // std::ofstream file(filename.c_str());
  FILE *file = fopen(filename.c_str(), "w");
  for (size_t i = 0 ; i < sample_num ; i++) {
    if (samples[i] != NULL) {
      // file << *samples[i];
      samples[i]->dump(file);
      delete samples[i];
    }
  }
#endif
}

bool simif_t::expect(bool pass, const char *s) {
  if (log) fprintf(stdout, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
  if (ok && !pass) fail_t = t;
  ok &= pass;
  return pass;
}

void simif_t::step(size_t n) {
#if ENABLE_SNAPSHOT
  // reservoir sampling
  if (t % trace_len == 0) {
    uint64_t start_time = 0;
    size_t record_id = t / trace_len;
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
#endif
  // take steps
  if (log) fprintf(stdout, "* STEP %u -> %llu *\n", n, (long long) (t + n));
  poke_channel(STEP_ADDR, n);
  for (size_t i = 0 ; i < POKE_SIZE ; i++) {
    poke_channel(CTRL_NUM + i, poke_map[i]);
  }
  while(!peek_channel(DONE_ADDR));
  for (size_t i = 0 ; i < PEEK_SIZE ; i++) {
    peek_map[i] = peek_channel(CTRL_NUM + i);
  }
  t += n;
  if (trace_count < trace_len) trace_count += n;
}

sample_t* simif_t::read_traces(sample_t *sample) {
  for (size_t i = 0 ; i < trace_count ; i++) {
    // input traces from FPGA
    for (idmap_it_t it = in_tr_map.begin() ; it != in_tr_map.end() ; it++) {
      size_t id = it->second;
      size_t chunk = out_chunks[id];
      uint32_t *data = new uint32_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = peek_channel(id+off);
      }
      if (sample) sample->add_cmd(new poke_t(it->first, data, chunk));
      delete[] data;
    }
    // sample->add_cmd(new step_t(1));
    // output traces from FPGA
    for (idmap_it_t it = out_tr_map.begin() ; it != out_tr_map.end() ; it++) {
      size_t id = it->second;
      size_t chunk = out_chunks[id];
      uint32_t *data = new uint32_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = peek_channel(id+off);
      }
      if (sample) sample->add_cmd(new expect_t(it->first, data, chunk));
      delete[] data;
    }
  }

  return sample;
}

static inline char* int_to_bin(char *bin, uint32_t value, size_t size) {
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
        if (type == SRAM_CHAIN) poke_channel(SRAM_RESTART_ADDR + i, 0);
        for (size_t j = 0 ; j < chain_len ; j++) {
          snap << int_to_bin(bin, peek_channel(CHAIN_ADDR[t] + i), DAISY_WIDTH);
        }
      }
    }
  }
  return new sample_t(snap.str().c_str(), cycles());
}
