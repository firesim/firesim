#include "debug_api.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <assert.h>
#include <iostream>
#include <fstream>
#include <sstream>
#include <stdlib.h>
#include <string.h>

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

debug_api_t::debug_api_t(std::string design_, bool trace_)
  : design(design_), trace(trace_), t(0), snaplen(0), pass(true), fail_t(-1), input_num(0), output_num(0)
{
  int fd = open("/dev/mem", O_RDWR|O_SYNC);
  assert(fd != -1);

  int host_prot = PROT_READ | PROT_WRITE;
  int flags = MAP_SHARED;
  uintptr_t pgsize = sysconf(_SC_PAGESIZE);
  assert(dev_paddr % pgsize == 0);

  dev_vaddr = (uintptr_t*)mmap(0, pgsize, host_prot, flags, fd, dev_paddr);
  assert(dev_vaddr != MAP_FAILED);

  // Reset
  write_reg(31, 0); 
  __sync_synchronize();
  // Empty output queues before starting!
  while ((uint32_t) read_reg(0) > 0) {
    uint32_t temp = (uint32_t) read_reg(1);
  }

  // Read mapping files
  read_io_map_file(design + ".io.map");
  read_chain_map_file(design + ".chain.map");

  // Remove snapshot files before getting started
  snapfilename = design + ".snap";
  remove(snapfilename.c_str());  

  srand(time(NULL));
}

debug_api_t::~debug_api_t() {
  std::cout << t << " Cycles";
  if (pass) 
    std::cout << " Passed" << std::endl;
  else 
    std::cout << " Failed, first at cycle " << fail_t << std::endl;
}

void debug_api_t::read_io_map_file(std::string filename) {
  std::ifstream file(filename.c_str());
  std::string line;
  bool isInput = false;
  if (file) {
    while (getline(file, line)) {
      std::istringstream iss(line);
      std::string head;
      iss >> head;
      if (head == "HOSTLEN:") iss >> hostlen;
      else if (head == "ADDRLEN:") iss >> addrlen;
      else if (head == "MEMLEN:") iss >> memlen;
      else if (head == "CMDLEN:") iss >> cmdlen;
      else if (head == "STEP:") iss >> _step;
      else if (head == "POKE:") iss >> _poke;
      else if (head == "PEEK:") iss >> _peek;
      else if (head == "SNAP:") iss >> _snap;
      else if (head == "MEM:") iss >> _mem;
      else if (head == "INPUT:") isInput = true;
      else if (head == "OUTPUT:") isInput = false;
      else {
        size_t width;
        iss >> width;
        size_t n = (width - 1) / hostlen + 1;
        if (isInput) {
          input_map[head] = std::vector<size_t>();
          for (int i = 0 ; i < n ; i++) {
            input_map[head].push_back(input_num);
            input_num++;
          }
        } else {
          output_map[head] = std::vector<size_t>();
          for (int i = 0 ; i < n ; i++) {
            output_map[head].push_back(output_num);
            output_num++;
          }
        }
      }
    }
  } else {
    std::cerr << "Cannot open " << filename << std::endl;
    exit(0);
  }
  file.close();
}

void debug_api_t::read_chain_map_file(std::string filename) {
  std::ifstream file(filename.c_str());
  std::string line;
  if (file) {
    while(getline(file, line)) {
      std::istringstream iss(line);
      std::string path;
      size_t width;
      iss >> path >> width;
      signals.push_back(path);
      widths.push_back(width);
      snaplen += width;
    }
  } else {
    std::cerr << "Cannot open " << filename << std::endl;
    exit(0);
  }
  file.close();
}

void debug_api_t::poke(uint64_t value) {
  write_reg(0, value);
  __sync_synchronize();
}

uint64_t debug_api_t::peek() {
  __sync_synchronize();
  while ((uint32_t) read_reg(0) == 0); 
  return (uint32_t) read_reg(1);
}

void debug_api_t::poke_all() {
  poke(_poke);
  for (int i = 0 ; i < input_num ; i++) {
    poke((poke_map.find(i) != poke_map.end()) ? poke_map[i] : 0);
    /*
    if (poke_map.find(i) != poke_map.end()) {
      poke(poke_map[i]);
    } else {
      poke(0);
    }*/
  }
}

void debug_api_t::peek_all() {
  peek_map.clear();
  poke(_peek);
  for (int i = 0 ; i < output_num ; i++) {
    peek_map[i] = peek();
  }
}

void debug_api_t::poke_snap() {
  poke(_snap);
}

void debug_api_t::poke_steps(size_t n) {
  poke(n << cmdlen | _step);
}

uint32_t debug_api_t::trace_mem() {
  uint32_t count = peek();
  for (int i = 0 ; i < count ; i++) {
    uint32_t addr = 0;
    for (int k = 0 ; k < addrlen ; k += hostlen) {
      addr = (addr << hostlen) | peek();
    }
    uint32_t data = 0;
    for (int k = 0 ; k < memlen ; k += hostlen) {
      data = (data << hostlen) | peek();
    }
    mem[addr] = data;
  }
  return count;
}

static inline char* int_to_bin(uint32_t value, size_t size) {
  char* bin = new char[size];
  for(int i = 0 ; i < size ; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  return bin;
}

void debug_api_t::read_snap(char *snap) {
  for (size_t offset = 0 ; offset < snaplen ; offset += hostlen) {
    char* value = int_to_bin(peek(), hostlen);
    memcpy(snap+offset, value, (offset+hostlen < snaplen) ? hostlen : snaplen-offset);
    delete[] value; 
  }
}

void debug_api_t::write_snap(char *snap, size_t n) {
  static bool begin_snap = false;
  FILE *file = fopen(snapfilename.c_str(), "a");
  if (file) {
    if (begin_snap) {
      fprintf(file, "STEP %x\n", n);
      for (std::map<std::string, std::vector<size_t> >::iterator it = output_map.begin() ; 
           it != output_map.end() ; it++) {
        std::string signal = it->first;
        std::vector<size_t> ids = it->second;
        uint32_t data = 0;
        for (int i = 0 ; i < ids.size() ; i++) {
          size_t id = ids[i];
          data = (data << hostlen) | ((peek_map.find(id) != peek_map.end()) ? peek_map[id] : 0);
        } 
        fprintf(file, "EXPECT %s %x\n", signal.c_str(), data);
      }
      fprintf(file, "//\n");
    }

    for (std::map<std::string, std::vector<size_t> >::iterator it = input_map.begin() ; 
         it != input_map.end() ; it++) {
      std::string signal = it->first;
      std::vector<size_t> ids = it->second;
      uint32_t data = 0;
      for (int i = 0 ; i < ids.size() ; i++) {
        size_t id = ids[i];
        data = (data << hostlen) | ((poke_map.find(id) != poke_map.end()) ? poke_map[id] : 0);
      } 
      fprintf(file, "%s %x\n", signal.c_str(), data);
    }
 
    size_t offset = 0;
    for (int i = 0 ; i < signals.size() ; i++) {
      std::string signal = signals[i];
      size_t width = widths[i];
      if (signal != "null") {
        char *bin = new char[width];
        uint32_t value = 0; // TODO: more than 32 bits?
        memcpy(bin, snap+offset, width);
        for (int i = 0 ; i < width ; i++) {
          value = (value << 1) | (bin[i] - '0'); // index?
        }
        fprintf(file, "%s %x\n", signal.c_str(), value);
        delete[] bin;
      }
      offset += width;
    }

    for (std::map<uint32_t, uint32_t>::iterator it = mem.begin() ; it != mem.end() ; it++) {
      uint32_t addr = it->first;
      uint32_t data = it->second;
      fprintf(file, "dram[%x] %08x\n", addr, data);
    }
    mem.clear();
  } else {
    std::cerr << "Cannot open " << snapfilename << std::endl;
    exit(0);
  }

  fclose(file);
  begin_snap = true;
}

void debug_api_t::step(size_t n) {
  char *snap = new char[snaplen];
  uint64_t target = t + n;
  if (trace) std::cout << "* STEP " << n << " -> " << target << " *" << std::endl;
  poke_all();
  poke_snap();
  poke_steps(n);
  while(trace_mem() > 0) {}
  read_snap(snap);
  peek_all();
  write_snap(snap, n);
  t += n;
  delete[] snap;
}

void debug_api_t::poke(std::string path, uint64_t value) {
  assert(input_map.find(path) != input_map.end());
  if (trace) std::cout << "* POKE " << path << " <- " << value << " *" << std::endl;
  std::vector<size_t> ids = input_map[path];
  uint64_t mask = (1 << hostlen) - 1;
  for (int i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    size_t shift = hostlen * i;
    uint32_t data = (value >> shift) & mask;
    poke_map[id] = data;
  }
}

uint64_t debug_api_t::peek(std::string path) {
  assert(output_map.find(path) != output_map.end());
  uint64_t value = 0;
  std::vector<size_t> ids = output_map[path];
  for (int i = 0 ; i < ids.size() ; i++) {
    size_t id = ids[ids.size()-1-i];
    assert(peek_map.find(id) != peek_map.end());
    value = value << hostlen | peek_map[id];
  }
  if (trace) std::cout << "* PEEK " << path << " -> " << value << " *" << std::endl;
  return value;
}

bool debug_api_t::expect(std::string path, uint64_t expected) {
  uint64_t value = peek(path);
  bool ok = value == expected;
  pass &= ok;
  if (!ok && fail_t < 0) fail_t = t;
  if (trace) std::cout << "* EXPECT " << path << " -> " << value << " == " << expected 
                       << (ok ? "PASS" : "FAIL") << " *" << std::endl;
  return ok;
}

bool debug_api_t::expect(bool ok, std::string s) {
  pass &= ok;
  if (!ok && fail_t < 0) fail_t = t;
  if (trace) std::cout << "* " << s << " " << (ok ? "PASS" : "FAIL") << " *" << std::endl;
  return ok;
}

void debug_api_t::load_mem(std::string filename) {
  std::ifstream file(filename.c_str());
  if (file) { 
    std::string line;
    int i = 0;
    while (std::getline(file, line)) {
      #define parse_nibble(c) ((c) >= 'a' ? (c)-'a'+10 : (c)-'0')
      uint64_t base = (i * line.length()) / 2;
      uint64_t offset = 0;
      for (int k = line.length() - 8 ; k >= 0 ; k -= 8) {
        uint64_t addr = base + offset;
        uint64_t data = 
          (parse_nibble(line[k]) << 28) | (parse_nibble(line[k+1]) << 24) |
          (parse_nibble(line[k+2]) << 20) | (parse_nibble(line[k+3]) << 16) |
          (parse_nibble(line[k+4]) << 12) | (parse_nibble(line[k+5]) << 8) |
          (parse_nibble(line[k+6]) << 4) | parse_nibble(line[k+7]);
        write_mem(base + offset, data);
        offset += 4;
      }
      i += 1;
    }
  } else {
    std::cerr << "Cannot open " << filename << std::endl;
    exit(1);
  }
  file.close();
}

void debug_api_t::write_mem(uint64_t addr, uint64_t data) {
  poke((1 << cmdlen) | _mem);
  uint64_t mask = (1<<hostlen)-1;
  for (int i = (addrlen-1)/hostlen+1 ; i > 0 ; i--) {
    poke((addr >> (hostlen * (i-1))) & mask);
  }
  for (int i = (memlen-1)/hostlen+1 ; i > 0 ; i--) {
    poke((data >> (hostlen * (i-1))) & mask);
  }

  mem[addr] = data;
}

uint64_t debug_api_t::read_mem(uint64_t addr) {
  poke((0 << cmdlen) | _mem);
  uint64_t mask = (1<<hostlen)-1;
  for (int i = (addrlen-1)/hostlen+1 ; i > 0 ; i--) {
    poke((addr >> (hostlen * (i-1))) & mask);
  }
  uint64_t data = 0;
  for (int i = 0 ; i < (memlen-1)/hostlen+1 ; i ++) {
    data |= peek() << (hostlen * i);
  }
  return data;
}

uint64_t debug_api_t::rand_next(size_t limit) {
  return rand() % limit;
}
