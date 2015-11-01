#include "simif.h"
#include <fstream>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log): prefix(_prefix), log(_log) 
{
  ok = true;
  t = 0;
  fail_t = 0;
  trace_count = 0;
  trace_len = TRACE_MAX_LEN;

  for (size_t i = 0 ; i < SAMPLE_NUM ; i++) {
    samples[i] = NULL;
  }
  last_sample = NULL;
  last_sample_id = 0;
  sample_split = false;

  profile = false;
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
  read_chain(prefix+".chain");
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

void simif_t::read_chain(std::string filename) {
  sample_t::init_chains();
  std::ifstream file(filename.c_str());
  if (file) {
    std::string line;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t type, width;
      int off;
      iss >> type >> path >> width >> off;
      if (path == "null") path = "";
      sample_t::add_to_chains(static_cast<CHAIN_TYPE>(type), path, width, off);
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::load_mem(std::string filename) {
  const size_t step = 1 << (MEM_BLOCK_OFFSET + 1);
  std::ifstream file(filename.c_str());
  if (file) {
    int i = 0;
    std::string line;
    while (std::getline(file, line)) {
      uint32_t base = (i * line.length()) >> 1;
      size_t offset = 0;
      for (int j = line.length() - step ; j >= 0 ; j -= step) {
        biguint_t data = 0;
        for (size_t k = 0 ; k < step ; k++) {
          data |= biguint_t(parse_nibble(line[j+k])) << (4*(step-1-k));
        }
        write_mem(base+offset, data);
        offset += step >> 1; // -> step / 2
      }
      i += 1;
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  } 
  file.close();
}

void simif_t::init() {
  for (auto &arg: hargs) {
    if (arg.find("+loadmem=") == 0) {
      std::string filename = arg.c_str()+9;
      fprintf(stdout, "[loadmem] start loading\n");
      load_mem(filename);
      fprintf(stdout, "[loadmem] done\n");
    }
    if (arg.find("+split") == 0) sample_split = true;
    if (arg.find("+profile") == 0) profile = true;
  }

  for (size_t i = 0 ; i < 5 ; i ++) {
    poke_channel(RESET_ADDR, 0);
    while(!peek_channel(0));
    recv_tokens(peek_map, PEEK_SIZE, 1);
    for (idmap_it_t it = out_tr_map.begin() ; it != out_tr_map.end() ; it++) {
      // flush traces from initialization
      biguint_t flush = peek_id(it->second);
    }
  }
  if (profile) sim_start_time = timestamp();
}

void simif_t::finish() {
  // tail samples
  if (last_sample != NULL) {
    if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
    samples[last_sample_id] = trace_ports(last_sample);
  }
  if (profile) {
    double sim_time = (double) (timestamp() - sim_start_time) / 1000000.0;
    fprintf(stdout, "Simulation Time: %.3f s, Sample Time: %.3f s\n", 
                    sim_time, (double) sample_time / 1000000.0);
  }
  // dump samples
  std::string filename = prefix + ".sample";
  std::ofstream file(filename.c_str());
  for (size_t i = 0 ; i < SAMPLE_NUM ; i++) {
    if (sample_split) {
      file.close();
      std::ostringstream oss;
      oss << prefix << "_" << i << ".sample";
      file.open(oss.str());
    }
    if (samples[i] != NULL) { 
      file << *samples[i];
      delete samples[i];
    }
  }
  file.close();
}

bool simif_t::expect(bool pass, const char *s) {
  if (log) fprintf(stdout, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
  if (ok && !pass) fail_t = t;
  ok &= pass;
  return pass;
}

void simif_t::step(size_t n) {
  if (log) fprintf(stdout, "* STEP %u -> %llu *\n", n, (long long) (t + n));
  // reservoir sampling
  if (t % trace_len == 0) {
    uint64_t start_time = 0;
    size_t record_id = t / trace_len;
    size_t sample_id = record_id < SAMPLE_NUM ? record_id : rand() % (record_id + 1);
    if (sample_id < SAMPLE_NUM) {
      if (profile) start_time = timestamp();
      if (last_sample != NULL) {
        if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
        samples[last_sample_id] = trace_ports(last_sample);
      }
      std::string snap = read_snapshot();
      last_sample = new sample_t(snap);
      last_sample_id = sample_id;
      trace_count = 0;
      if (profile) sample_time += (timestamp() - start_time);
    } 
  }

  // take steps
  poke_channel(0, n);
  send_tokens(poke_map, POKE_SIZE, 1);
  while(!peek_channel(0));
  recv_tokens(peek_map, PEEK_SIZE, 1);
  t += n;
  if (trace_count < trace_len) trace_count += n;
}

void simif_t::write_mem(size_t addr, biguint_t data) {
  poke_channel(MEM_REQ_ADDR, addr >> MEM_BLOCK_OFFSET);
  poke_channel(MEM_REQ_TAG,  0);
  poke_channel(MEM_REQ_RW,   1);
  for (size_t off = 0 ; off < MEM_DATA_CHUNK ; off++) {
    poke_channel(MEM_REQ_DATA+off, (data >> (off << CHANNEL_OFFSET)).uint());
  }
}

biguint_t simif_t::read_mem(size_t addr) {
  poke_channel(MEM_REQ_ADDR, addr >> MEM_BLOCK_OFFSET);
  poke_channel(MEM_REQ_TAG,  0);
  poke_channel(MEM_REQ_RW,   0);

  biguint_t data = 0;
  for (size_t off = 0 ; off < MEM_DATA_CHUNK; off++) {
    assert(peek_channel(MEM_RESP_TAG) == 0);
    data |= peek_channel(MEM_RESP_DATA+off) << (off << CHANNEL_OFFSET);
  }
  return data;
}

sample_t* simif_t::trace_ports(sample_t *sample) {
  for (size_t i = 0 ; i < trace_count ; i++) {
    // input traces from FPGA
    for (idmap_it_t it = in_tr_map.begin() ; it != in_tr_map.end() ; it++) {
      std::string wire = it->first;
      sample->add_cmd(new poke_t(wire, peek_id(it->second)));
    }
    sample->add_cmd(new step_t(1));
    // output traces from FPGA
    for (idmap_it_t it = out_tr_map.begin() ; it != out_tr_map.end() ; it++) {
      std::string wire = it->first;
      sample->add_cmd(new expect_t(wire, peek_id(it->second)));
    }
  }

  return sample;
}

static inline char* int_to_bin(char *bin, uint64_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
  return bin;
}

std::string simif_t::read_snapshot() {
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1];
  
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    for (size_t k = 0 ; k < CHAIN_LOOP[t]; k++) {
      if (type == SRAM_CHAIN) poke_channel(SRAM_RESTART_ADDR, 0);
      for (size_t i = 0 ; i < CHAIN_LEN[t]; i++) {
        snap << int_to_bin(bin, peek_channel(CHAIN_ADDR[t]), DAISY_WIDTH);
      }
    }
  }
  return snap.str();
}
