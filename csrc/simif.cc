#include "simif.h"
#include <fstream>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log): prefix(_prefix), log(_log)
{
  ok = true;
  t = 0;
  fail_t = 0;

  for (size_t i = 0 ; i < SAMPLE_NUM ; i++) {
    samples[i] = NULL;
  }
  last_sample = NULL;
  last_sample_id = 0;
  sample_split = false;

  srand(time(NULL));

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
  fprintf(stdout, "\n");
}

void simif_t::read_map(std::string filename) {
  enum MAP_TYPE { IO_IN, IO_OUT, IN_TRACE, OUT_TRACE };
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
        case IN_TRACE:
          in_trace_map[path] = id;
          out_chunks[id] = chunk;
          break;
        case OUT_TRACE:
          out_trace_map[path] = id;
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
  enum CHAIN_TYPE { REGS, TRACE, SRAM, CNTR };
  std::ifstream file(filename.c_str());
  if (file) {
    std::string line;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t type, width;
      int off;
      iss >> type >> path >> width >> off;
      switch (static_cast<CHAIN_TYPE>(type)) {
        case REGS:
          sample_t::add_to_reg_chains(path, width, off);
          break;
        case TRACE:
          sample_t::add_to_trace_chains(path, width);
          break;
        case SRAM:
          sample_t::add_to_sram_chains(path, width, off);
          break;
        case CNTR:
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
  }

  peek_map.clear();
  for (size_t i = 0 ; i < in_map.size() ; i++) {
    in_traces.push_back(trace_t());
  }
  for (idmap_it_t it = out_map.begin() ; it != out_map.end() ; it++) {
    size_t id = it->second;
    peek_map[id] = peek_port(id);
    out_traces.push_back(trace_t());
  }
  for (idmap_it_t it = out_trace_map.begin() ; it != out_trace_map.end() ; it++) {
    // flush traces from initialization
    biguint_t flush = peek_port(it->second);
  }
}

void simif_t::finish() {
  // tail samples
  if (last_sample != NULL) {
    if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
    samples[last_sample_id] = trace_ports(last_sample);
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

void simif_t::poke_port(std::string path, biguint_t value) {
  assert(in_map.find(path) != in_map.end());
  if (log) fprintf(stdout, "* POKE %s <- %s *\n", path.c_str(), value.str().c_str());
  poke_map[in_map[path]] = value;
}

biguint_t simif_t::peek_port(std::string path) {
  assert(out_map.find(path) != out_map.end());
  assert(peek_map.find(out_map[path]) != peek_map.end());
  biguint_t value = peek_map[out_map[path]];
  if (log) fprintf(stdout, "* PEEK %s -> %s *\n", path.c_str(), value.str().c_str());
  return value;
}

bool simif_t::expect(bool pass, const char *s) {
  if (log) fprintf(stdout, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
  if (ok && !pass) fail_t = t;
  ok &= pass;
  return pass;
}

bool simif_t::expect_port(std::string path, biguint_t expected) {
  assert(out_map.find(path) != out_map.end());
  assert(peek_map.find(out_map[path]) != peek_map.end());
  biguint_t value = peek_map[out_map[path]];
  bool pass = value == expected;
  std::ostringstream oss;
  oss << "EXPECT " << path << " " << value << " == " << expected;
  return expect(pass, oss.str().c_str());
}

void simif_t::step(size_t n) {
  if (log) fprintf(stdout, "* STEP %u -> %llu *\n", n, (long long) (t + n));
    
  for (size_t i = 0 ; i < n ; i++) {
    // reservoir sampling
    if (t % TRACE_LEN == 0) {
      size_t record_id = t / TRACE_LEN;
      size_t sample_id = record_id < SAMPLE_NUM ? record_id : rand() % (record_id + 1);
      if (sample_id < SAMPLE_NUM) {
        if (last_sample != NULL) {
          if (samples[last_sample_id] != NULL) delete samples[last_sample_id];
          samples[last_sample_id] = trace_ports(last_sample);
        }
        std::string snap = read_snapshot();
        last_sample = new sample_t(snap);
        last_sample_id = sample_id;
        trace_count = 0;
      }
    }

    // take a step
    size_t k;
    idmap_it_t it;
    for (it = in_map.begin(), k = 0 ; it != in_map.end() ; it++, k++) {
      size_t id = it->second;
      biguint_t data = poke_map.find(id) != poke_map.end() ? poke_map[id] : 0;
      poke_port(id, data);
      if (trace_count < TRACE_LEN) {
        in_traces[k].push(data);
      } 
    }
    peek_map.clear();
    for (it = out_map.begin(), k = 0 ; it != out_map.end() ; it++, k++) {
      size_t id = it->second;
      biguint_t data = peek_port(id); 
      peek_map[id] = data; 
      if (trace_count < TRACE_LEN) {
        out_traces[k].push(data);
      } 
    }

    t++;
    if (trace_count < TRACE_LEN) {
      trace_count++;
    }  
  }
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
  size_t len = in_traces[0].size(); // can be less than TRACE_LEN, but same for all traces
  for (size_t i = 0 ; i < len ; i++) {
    // input traces by the driver
    for (idmap_it_t it = in_map.begin() ; it != in_map.end() ; it++) {
      std::string wire = it->first;
      trace_t &trace = in_traces[it->second];
      assert(trace.size() + i == len);
      sample->add_cmd(new poke_t(wire, trace.front()));
      trace.pop();
    }
    // input traces from FPGA
    for (idmap_it_t it = in_trace_map.begin() ; it != in_trace_map.end() ; it++) {
      std::string wire = it->first;
      sample->add_cmd(new poke_t(wire, peek_port(it->second)));
    }
    sample->add_cmd(new step_t(1));
    // output traces by the driver
    for (idmap_it_t it = out_map.begin() ; it != out_map.end() ; it++) {
      std::string wire = it->first;
      trace_t &trace = out_traces[it->second];
      assert(trace.size() + i == len);
      sample->add_cmd(new expect_t(wire, trace.front()));
      trace.pop();
    }
    // output traces from FPGA
    for (idmap_it_t it = out_trace_map.begin() ; it != out_trace_map.end() ; it++) {
      std::string wire = it->first;
      sample->add_cmd(new expect_t(wire, peek_port(it->second)));
    }
  }

  return sample;
}

static inline void int_to_bin(char *bin, uint64_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
}

std::string simif_t::read_snapshot() {
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1];

  for (size_t k = 0 ; k < SRAM_MAX_SIZE ; k++) {
    poke_channel(SRAM_RESTART_ADDR, 0);
    for (size_t i = 0 ; i < SRAM_SNAP_LEN ; i++) {
      int_to_bin(bin, peek_channel(SNAP_OUT_SRAM), DAISY_WIDTH);
      snap << bin;
    }
  }
  for (size_t i = 0 ; i < TRACE_SNAP_LEN ; i++) {
    int_to_bin(bin, peek_channel(SNAP_OUT_TRACE), DAISY_WIDTH);
    snap << bin;
  } 
  for (size_t i = 0 ; i < REG_SNAP_LEN ; i++) {
    int_to_bin(bin, peek_channel(SNAP_OUT_REGS), DAISY_WIDTH);
    snap << bin;
  }

  return snap.str();
}
