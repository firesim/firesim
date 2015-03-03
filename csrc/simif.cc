#include "simif.h"
#include <fstream>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

simif_t::simif_t(std::vector<std::string> args, std::string _prefix,  bool _log, bool _sample_check): 
  log(_log), sample_check(_sample_check)
{
  // initialization
  pass = true;
  is_done = false;
  t = 0;
  fail_t = -1;
  max_cycles = -1; 
  snap_len = 0;
  sample_num = 200;
  step_size = 1;
  loadmem = ""; 
  prefix = _prefix;

  qin_num = 0;
  qout_num = 0;
  win_num = 0;
  wout_num = 0;

  srand(time(NULL));

  // Read mapping files
  read_io_map(prefix+".io.map");
  read_chain_map(prefix+".chain.map");

  size_t i;
  for (i = 0 ; i < args.size() ; i++) {
    if (args[i].length() && args[i][0] != '-' && args[i][0] != '+')
      break;
  }
  hargs.insert(hargs.begin(), args.begin(), args.begin() + i);
  targs.insert(targs.begin(), args.begin() + i, args.end());

  for (auto &arg: hargs) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str()+12);
    } else if (arg.find("+step-size=") == 0) {
      step_size = atoi(arg.c_str()+11);
    } else if (arg.find("+sample-num=") == 0) {
      sample_num = atoi(arg.c_str()+12);
    } else if (arg.find("+loadmem=") == 0) {
      loadmem = arg.c_str()+9;
    }
  }

  // Set the prefix of sample files
  if (!targs.empty()) {
    size_t pos = targs.back().rfind('/'); 
    prefix += "." + targs.back().substr(pos+1);
  } else if (loadmem != "") {
    size_t s_pos = loadmem.rfind("/"); 
    size_t e_pos = loadmem.rfind(".");
    prefix += "." + loadmem.substr(s_pos+1, e_pos-(s_pos+1));
  }
}

simif_t::~simif_t() {
  // Dump samples
  mkdir("samples", S_IRUSR | S_IWUSR | S_IXUSR);
  std::string filename = "samples/" + prefix + ".sample";
  std::ofstream file(filename.c_str());
  if (file) {
    sample_t::dump(file, samples);
    file.close();
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  for (size_t i = 0 ; i < samples.size() ; i++) {
    delete samples[i];
  }
}

void simif_t::read_io_map(std::string filename) {
  enum IOType { QIN, QOUT, WIN, WOUT };
  IOType iotype = QIN;
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while (getline(file, line)) {
      std::istringstream iss(line);
      std::string head;
      iss >> head;
      if (head == "QIN:") iotype = QIN;
      else if (head == "QOUT:") iotype = QOUT;
      else if (head == "WIN:") iotype = WIN;
      else if (head == "WOUT:") iotype = WOUT;
      else {
        size_t width;
        iss >> width;
        size_t n = (width-1)/HOST_LEN + 1;
        switch (iotype) {
          case QIN:
            qin_map[head] = std::vector<size_t>();
            for (size_t i = 0 ; i < n ; i++) {
              qin_map[head].push_back(qin_num);
              qin_num++;
            }
            break;
          case QOUT:
            qout_map[head] = std::vector<size_t>();
            for (size_t i = 0 ; i < n ; i++) {
              qout_map[head].push_back(qout_num);
              qout_num++;
            }
            break;
          case WIN:
            win_map[head] = std::vector<size_t>();
            for (size_t i = 0 ; i < n ; i++) {
              win_map[head].push_back(win_num);
              win_num++;
            }
            break;
          case WOUT:
            wout_map[head] = std::vector<size_t>();
            for (size_t i = 0 ; i < n ; i++) {
              wout_map[head].push_back(wout_num);
              wout_num++;
            }
            break;
          default:
            break;
        }
      }
    }
    for (size_t i = 0 ; i < win_num ; i++) {
      poke_map[i] = 0;
    }
    for (size_t i = 0 ; i < qin_num ; i++) {
      pokeq_map[i] = std::queue<uint32_t>();
    }
    for (size_t i = 0 ; i < qout_num ; i++) {
      peekq_map[i] = std::queue<uint32_t>();
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::read_chain_map(std::string filename) {
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while(getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t width;
      iss >> path >> width;
      sample_t::add_mapping(path, width);
      snap_len += width;
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(0);
  }
  file.close();
}

void simif_t::poke_steps(size_t n, bool read_next) {
  poke_host(n << (CMD_LEN+1) | read_next << CMD_LEN | STEP_CMD);
}

void simif_t::poke_all() {
  poke_host(POKE_CMD);
  for (size_t i = 0 ; i < win_num ; i++) {
    poke_host(poke_map[i]);
  }
}

void simif_t::peek_all() {
  peek_map.clear();
  poke_host(PEEK_CMD);
  for (size_t i = 0 ; i < wout_num ; i++) {
    peek_map[i] = peek_host();
  }
}

void simif_t::pokeq_all() {
  if (qin_num > 0) poke_host(POKEQ_CMD);
  for (size_t i = 0 ; i < qin_num ; i++) {
    uint32_t count = (pokeq_map[i].size() < CMD_LEN) ? pokeq_map[i].size() : CMD_LEN;
    poke_host(count);
    for (size_t k = 0 ; k < count ; k++) {
      poke_host(pokeq_map[i].front());
      pokeq_map[i].pop();
    }
  }
}

void simif_t::peekq_all() {
  if (qout_num > 0) poke_host(PEEKQ_CMD);
  trace_qout();
}

void simif_t::trace_qout() {
  for (size_t i = 0 ; i < qout_num ; i++) {
    uint32_t count = peek_host();
    for (size_t k = 0 ; k < count ; k++) {
      peekq_map[i].push(peek_host());
    }
  }
}

void simif_t::peek_trace() {
  poke_host(TRACE_CMD);
  trace_mem();
}

void simif_t::trace_mem() {
  std::vector<uint64_t> waddr;
  std::vector<uint64_t> wdata;
  uint32_t wcount = peek_host();
  for (size_t i = 0 ; i < wcount ; i++) {
    uint64_t addr = 0;
    for (size_t k = 0 ; k < MIF_ADDR_BITS ; k += HOST_LEN) {
      addr |= peek_host() << k;
    }
    waddr.push_back(addr);
  }
  for (size_t i = 0 ; i < wcount ; i++) {
    uint64_t data = 0;
    for (size_t k = 0 ; k < MIF_DATA_BITS ; k += HOST_LEN) {
      data |= peek_host() << k;
    }
    wdata.push_back(data);
  }
  for (size_t i = 0 ; i < wcount ; i++) {
    uint64_t addr = waddr[i];
    uint64_t data = wdata[i];
    mem_writes[addr] = data;
  }
  waddr.clear();
  wdata.clear();

  uint32_t rcount = peek_host();
  for (size_t i = 0 ; i < rcount ; i++) {
    uint64_t addr = 0;
    for (size_t k = 0 ; k < MIF_ADDR_BITS ; k += HOST_LEN) {
      addr = (addr << HOST_LEN) | peek_host();
    }
    uint64_t tag = 0;
    for (size_t k = 0 ; k < MIF_TAG_BITS ; k += HOST_LEN) {
      tag = (tag << HOST_LEN) | peek_host();
    }
    mem_reads[tag] = addr;
  }
}

static inline void int_to_bin(char *bin, uint32_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
}

std::string simif_t::read_snap() {
  std::ostringstream snap;
  char bin[HOST_LEN+1];
  while(peek_host_ready()) {
    int_to_bin(bin, peek_host(), HOST_LEN);
    snap << bin;
  }
  assert(snap.str().size() == snap_len);
  return snap.str();
}

void simif_t::step(size_t n) {
  static uint64_t last_r = 0;
  static bool read_next = false;
  assert(n % step_size == 0);
  if (log) fprintf(stdout, "* STEP %u -> %llu *\n", n, (long long) (t + n));
  if (read_next) record_io(last_r, n);
  poke_all();
  pokeq_all();
  size_t index = t / step_size;
  size_t r = index < sample_num ? index : rand_next(index+1);
  read_next = sample_check || r < sample_num;
  poke_steps(n, read_next);
  bool fin = false;
  while (!fin) {
    if (peek_host_ready()) {
      uint32_t resp = peek_host();
      if (resp == RESP_FIN) fin = true;
      else if (resp == RESP_TRACE) trace_mem();
      else if (resp == RESP_PEEKQ) trace_qout();
    }
  }
  if (read_next) {
    do_sampling(index, r, last_r);
    last_r = r;
  }
  peek_all();
  peekq_all();
  t += n;
}

void simif_t::record_io(size_t r, size_t n) {
  sample_t *sample = sample_check ? samples.back() : samples[r];
  if (sample_check) {
    for (iomap_t::const_iterator it = wout_map.begin() ; it != wout_map.end() ; it++) {
      std::string signal = it->first;
      std::vector<size_t> ids = it->second;
      biguint_t data = 0;
      for (size_t i = 0 ; i < ids.size() ; i++) {
        size_t id = ids[ids.size()-1-i];
        assert(peek_map.find(id) != peek_map.end());
        data = data << HOST_LEN | peek_map[id];        
      }
      sample->add_cmd(new expect_t(signal, data));
    }
  }
  for (iomap_t::const_iterator it = win_map.begin() ; it != win_map.end() ; it++) {
    std::string signal = it->first;
    std::vector<size_t> ids = it->second;
    biguint_t data = 0;
    for (size_t i = 0 ; i < ids.size() ; i++) {
      size_t id = ids[i];
      data = (data << HOST_LEN) | ((poke_map.find(id) != poke_map.end()) ? poke_map[id] : 0);
    }
    sample->add_cmd(new poke_t(signal, data));
  }
  if (sample_check) sample->add_cmd(new step_t(n));
}

void simif_t::do_sampling(size_t index, size_t r, size_t last_r) {
  std::string snap = read_snap();
  sample_t* sample = new sample_t(index, snap);

  peek_trace();
  for (addr_t::const_iterator it = mem_reads.begin() ; it != mem_reads.end(); it++) {
    size_t tag = it->first;
    uint64_t addr = it->second;
    sample->add_cmd(new read_t(addr, tag)); 
  }
  for (mem_t::const_iterator it = mem_writes.begin() ; it != mem_writes.end() ; it++) {
    uint64_t addr = it->first;
    biguint_t data = it->second;
    sample->add_mem(addr, data);
  }
  mem_writes.clear();
  mem_reads.clear();

  if (sample_check || samples.size() < sample_num) {
    if (!samples.empty()) {
      sample_t *last = samples.back();
      last->next = sample;
      sample->prev = last;
    }
    samples.push_back(sample);
  } else {
    sample_t* this_sample = samples[r];
    if (sample_t* next = this_sample->next) {
      // mem merge
      (*next) += (*this_sample);
      if (sample_t* prev = this_sample->next) {
        prev->next = next;
        next->prev = prev;
      }
    } else {
      assert(r == last_r);
      // mem merge
      (*sample) += (*this_sample);
      if (sample_t* prev = this_sample->prev) {
        sample->prev = prev;
        prev->next = sample;
      }
    }
    sample_t* last = samples[last_r];
    sample->prev = last;
    last->next = sample;
    delete samples[r];
    samples[r] = sample;
  }  
}

void simif_t::poke(std::string path, biguint_t& value) {
  assert(win_map.find(path) != win_map.end());
  std::vector<size_t> ids = win_map[path];
  uint64_t mask = (uint64_t(1) << HOST_LEN) - 1;
  for (size_t i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    size_t shift = HOST_LEN * i;
    uint32_t data = (value >> shift).uint() & mask;
    poke_map[id] = data;
  }
  if (log) fprintf(stdout, "* POKE %s %llu *\n", path.c_str(), value.uint());
}

biguint_t simif_t::peek(std::string path) {
  assert(wout_map.find(path) != wout_map.end());
  biguint_t value = 0;
  std::vector<size_t> ids = wout_map[path];
  for (size_t i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    assert(peek_map.find(id) != peek_map.end());
    value = value << HOST_LEN | peek_map[id];
  }
  if (log) fprintf(stdout, "* PEEK %s -> %llu *\n", path.c_str(), value.uint());
  return value;
}

void simif_t::pokeq(std::string path, biguint_t& value) {
  assert(qin_map.find(path) != qin_map.end());
  std::vector<size_t> ids = qin_map[path];
  uint64_t mask = (uint64_t(1) << HOST_LEN) - 1;
  for (size_t i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    size_t shift = HOST_LEN * i;
    uint32_t data = (value >> shift).uint() & mask;
    assert(pokeq_map.find(id) != pokeq_map.end());
    pokeq_map[id].push(data);
  }
  if (log) fprintf(stdout, "* POKEQ %s <- %llu *\n", path.c_str(), value.str().c_str());
}

biguint_t simif_t::peekq(std::string path) {
  assert(qout_map.find(path) != qout_map.end());
  std::vector<size_t> ids = qout_map[path];
  biguint_t value = 0;
  for (size_t i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    assert(peekq_map.find(id) != peekq_map.end());
    value = value << HOST_LEN | peekq_map[id].front();
    peekq_map[id].pop();
  }
  if (log) fprintf(stdout, "* PEEKQ %s <- %llu *\n", path.c_str(), value.str().c_str());
  return value;
}

bool simif_t::peekq_valid(std::string path) {
  assert(qout_map.find(path) != qout_map.end());
  std::vector<size_t> ids = qout_map[path];
  bool valid = true;
  for (size_t i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    assert(peekq_map.find(id) != peekq_map.end());
    valid &= !peekq_map[id].empty();
  }
  return valid;
}

bool simif_t::expect(std::string path, biguint_t &expected) {
  biguint_t value = peek(path);
  bool ok = value == expected;
  pass &= ok;
  if (!ok && t < fail_t) fail_t = t;
  if (log) fprintf(stdout, "* EXPECT %s -> %llu === %llu %s *\n", 
    path.c_str(), value.str().c_str(), expected.str().c_str(), ok ? " PASS" : " FAIL");
  return ok;
}

bool simif_t::expect(bool ok, const char *s) {
  pass &= ok;
  if (!ok && fail_t < 0) fail_t = t;
  if (log) fprintf(stdout, "* %s %s *\n", s, ok ? "PASS" : "FAIL");
  return ok;
}

void simif_t::load_mem() {
  const size_t blk_len = MIF_DATA_BITS / 4;
  const size_t chunk_num = MIF_DATA_BITS / 32;
  std::ifstream file(loadmem.c_str());
  if (file) { 
    std::string line;
    int i = 0;
    while (std::getline(file, line)) {
      assert(line.length() % blk_len == 0);
      uint64_t base = (i * line.length()) / 2;
      size_t offset = 0;
      for (int k = line.length() - blk_len ; k >= 0 ; k -= blk_len) {
        biguint_t data = 0;
        for (int j = 0 ; j < chunk_num ; j++) {
          size_t s = k + 8*j;
          uint32_t chunk = (parse_nibble(line[s]) << 28) | (parse_nibble(line[s+1]) << 24) |
            (parse_nibble(line[s+2]) << 20) | (parse_nibble(line[s+3]) << 16) |
            (parse_nibble(line[s+4]) << 12) | (parse_nibble(line[s+5]) << 8) |
            (parse_nibble(line[s+6]) << 4) | parse_nibble(line[s+7]);
          data = (data << HOST_LEN) | chunk;
        }
        write_mem(base+offset, data);
        offset += 4;
      }
      i += 1;
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", loadmem.c_str());
    exit(1);
  }
  file.close();
}

void simif_t::write_mem(uint64_t addr, biguint_t &data) {
  poke_host((1 << CMD_LEN) | MEM_CMD);
  uint64_t mask = (uint64_t(1) << HOST_LEN)-1;
  for (size_t i = (MIF_ADDR_BITS-1)/HOST_LEN+1 ; i > 0 ; i--) {
    poke_host((addr >> (HOST_LEN * (i-1))) & mask);
  }
  for (size_t i = (MIF_DATA_BITS-1)/HOST_LEN+1 ; i > 0 ; i--) {
    poke_host((data >> (HOST_LEN * (i-1))).uint() & mask);
  }
}

biguint_t simif_t::read_mem(uint64_t addr) {
  poke_host((0 << CMD_LEN) | MEM_CMD);
  uint64_t mask = (uint64_t(1) << HOST_LEN)-1;
  for (size_t i = (MIF_ADDR_BITS-1) / HOST_LEN + 1 ; i > 0 ; i--) {
    poke_host((addr >> (HOST_LEN * (i-1))) & mask);
  }
  biguint_t data = 0;
  for (size_t i = 0 ; i < (MIF_DATA_BITS-1) / HOST_LEN + 1 ; i ++) {
    data = data | biguint_t(peek_host()) << (HOST_LEN * i);
  }
  return data;
}
