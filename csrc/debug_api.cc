#include "debug_api.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <assert.h>
#include <iostream>
#include <fstream>
#include <bitset>
#include <stdlib.h>

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

debug_api_t::debug_api_t(std::string design_, bool trace_)
  : design(design_), trace(trace_), t(0), snap_size(0), pass(true), fail_t(-1), input_num(0), output_num(0)
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
    uint32_t temp = read_reg(1);
  }

  // Read mapping files
  read_io_map_file(design + ".io.map");
  read_chain_map_file(design + ".chain.map");

  srand(time(NULL));
}

debug_api_t::~debug_api_t() {
  std::cout << t << " Cycles";
  if (pass) 
    std::cout << " Passed" << std::endl;
  else 
    std::cout << " Failed, first at cycle " << fail_t << std::endl;

  write_replay_file(design + ".replay");
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
      if (head == "HOSTWIDTH:") iss >> hostwidth;
      else if (head == "OPWIDTH:") iss >> opwidth;
      else if (head == "STEP:") iss >> STEP;
      else if (head == "POKE:") iss >> POKE;
      else if (head == "PEEK:") iss >> PEEK;
      else if (head == "SNAP:") iss >> SNAP;
      else if (head == "INPUT:") isInput = true;
      else if (head == "OUTPUT:") isInput = false;
      else {
        int width;
        iss >> width;
        if (isInput) {
          int n = (width - 1) / (hostwidth - 1) + 1;
          input_map[head] = std::vector<int>();
          for (int i = 0 ; i < n ; i++) {
            input_map[head].push_back(input_num);
            input_num++;
          }
        } else {
          int n = (width - 1) / hostwidth + 1;
          output_map[head] = std::vector<int>();
          for (int i = 0 ; i < n ; i++) {
            output_map[head].push_back(output_num);
            output_num++;
          }
        }
      }
    }
  } else {
    std::cout << "Cannot open " << filename << std::endl;
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
      int width;
      iss >> path >> width;
      signals.push_back(path);
      widths.push_back(width);
      snap_size += width;
    }
  } else {
    std::cout << "Cannot open " << filename << std::endl;
  }
  file.close();
}

void debug_api_t::write_replay_file(std::string filename) {
  std::ofstream file(filename.c_str());
  if (file) {
    file << replay.str();    
  } else {
    std::cout << "Cannot open " << filename << std::endl;
  }
  file.close();
}

void debug_api_t::poke_all() {
  write_reg(0, POKE);
  __sync_synchronize();

  for (int i = 0 ; i < input_num ; i++) {
    if (poke_map.find(i) != poke_map.end()) {
      write_reg(0, poke_map[i] << 1 | 1);
      __sync_synchronize();
    } else {
      write_reg(0, 0);
      __sync_synchronize();
    }
  }
  poke_map.clear();
}

void debug_api_t::peek_all() {
  peek_map.clear();
  write_reg(0, PEEK);
  __sync_synchronize();

  int i = 0;
  while (i < output_num) {
    __sync_synchronize();
    uint32_t c = (uint32_t) read_reg(0);
    if (c > 0) {
      for (int j = 0 ; j < c ; j++) {
        peek_map[i] = (uint32_t) read_reg(1);
        i++;
      }
    }
  }
}

void debug_api_t::snapshot(std::string &snap) {
  int i = 0;
  int limit = snap_size / hostwidth;
  while (i < limit) {
    __sync_synchronize();
    uint32_t c = (uint32_t) read_reg(0);
    if (c > 0) {
      for (int j = 0 ; j < c ; j++) {
        uint32_t value = (uint32_t) read_reg(1);
        std::bitset<sizeof(uint32_t) * 8> bin_value(value);
        snap += bin_value.to_string();
        i++;
        if (i >= limit) break;
      }
    }
  }
}

void debug_api_t::write_snap(std::string &snap, uint32_t n) {
  static bool begin = false;
  if (begin) {
    replay << "STEP " << n << std::endl;
    for (int i = 0 ; i < outputs.size() ; i++) {
      std::string output = outputs[i];
      replay << "EXPECT " << output << " " << peek(output) << std::endl;
    }
  }

  // Translate and write snapshots
  int offset = 0;
  for (int i = 0 ; i < signals.size() ; i++) {
    std::string signal = signals[i];
    int width = widths[i];
    if (signal != "null") {
      std::bitset<512> value(snap.substr(offset, width));
      replay << "POKE " << signal << " " << value.to_ulong() << std::endl;
    }
    offset += width;
  }
  begin = true;
}

void debug_api_t::poke_snap() {
  write_reg(0, SNAP);
  __sync_synchronize();
}

void debug_api_t::poke_steps(uint32_t n) {
  write_reg(0, n << opwidth | STEP);
  __sync_synchronize();
}

void debug_api_t::step(uint32_t n) {
  std::string snap = "";
  poke_all();
  if (t > 0) poke_snap();
  poke_steps(n);
  if (t > 0) snapshot(snap);
  peek_all();
  if (trace) std::cout << "* STEP " << n << " -> " << (t + n) << " * " << std::endl;

  if (t > 0) write_snap(snap, n);
  t += n;
}

void debug_api_t::poke(std::string path, uint64_t value) {
  assert(input_map.find(path) != input_map.end());
  if (trace) std::cout << "* POKE " << path << " <- " << value << " * " << std::endl;
  std::vector<int> ids = input_map[path];
  for (int i = 0 ; i < ids.size() ; i++) {
    int id = ids[ids.size()-1-i];
    uint32_t mask = (value >> (i * (hostwidth - 1))) & ((1 << (hostwidth - 1)) - 1);
    poke_map[id] = mask;
  }
}

uint64_t debug_api_t::peek(std::string path) {
  assert(output_map.find(path) != output_map.end());
  uint64_t value = 0;
  std::vector<int> ids = output_map[path];
  for (int i = 0 ; i < ids.size() ; i++) {
    int id = ids[ids.size()-1-i];
    assert(peek_map.find(id) != peek_map.end());
    value = value << hostwidth | peek_map[id];
  }
  if (trace) std::cout << "* PEEK " << path << " -> " << value << " * " << std::endl;
  return value;
}

bool debug_api_t::expect(std::string path, uint64_t expected) {
  int value = peek(path);
  bool ok = value == expected;
  pass &= ok;
  if (trace) {
    std::cout << "* EXPECT " << path << " -> " << value << " == " << expected;
    if (ok) {
      std::cout << " PASS * " << std::endl;
    }  else {
      if (fail_t < 0) fail_t = t;
      std::cout << " FAIL * " << std::endl;
    }
  }
  return ok;
}

bool debug_api_t::expect(bool ok, std::string s) {
  pass &= ok;
  if (trace) {
    std::cout << "* " << s;
    if (ok) {
      std::cout << " PASS * " << std::endl;
    } else {
      if (fail_t < 0) fail_t = t;
      std::cout << " FAIL * " << std::endl;
    }
  }
  return ok;
} 

uint64_t debug_api_t::rand_next(int limit) {
  return rand() % limit;
}
