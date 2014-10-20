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

#define HOSTWIDTH 32
#define OPWIDTH 6
#define STEP 0
#define POKE 1
#define PEEK 2
#define SNAP 3

debug_api_t::debug_api_t(std::string design_)
  : t(0), snap_size(0), pass(true), fail_t(-1), design(design_)
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
      if (line == "INPUT:") {
        isInput = true;
      } else if (line == "OUTPUT:") {
        isInput = false;
      } else {
        std::istringstream iss(line);
        std::string path;
        int id;
        iss >> path >> id;
        if (isInput) {
          input_map[path] = id;
        } else {
          outputs.push_back(path);
          output_map[path] = id;
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

  for (int i = 0 ; i < input_map.size() ; i++) {
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
  int limit = output_map.size();
  while (i < limit) {
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
  int limit = snap_size / HOSTWIDTH;
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
      int idx = output_map[output];
      replay << "EXPECT " << output << " " << peek_map[idx] << std::endl;
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
  write_reg(0, n << OPWIDTH | STEP);
  __sync_synchronize();
}

void debug_api_t::step(uint32_t n) {
  std::string snap = "";
  poke_all();
  if (t > 0) poke_snap();
  poke_steps(n);
  if (t > 0) snapshot(snap);
  std::cout << "* STEP " << n << " -> " << (t + n) << " * " << std::endl;
  peek_all();

  if (t > 0) write_snap(snap, n);
  t += n;
}

void debug_api_t::poke(std::string path, uint32_t value) {
  assert(input_map.find(path) != input_map.end());
  poke_map[input_map[path]] = value;
  std::cout << "* POKE " << path << " <- " << value << " * " << std::endl;
}

uint32_t debug_api_t::peek(std::string path) {
  assert(output_map.find(path) != output_map.end());
  assert(peek_map.find(output_map[path]) != peek_map.end());
  uint32_t value = peek_map[output_map[path]];
  std::cout << "* PEEK " << path << " -> " << value << " * " << std::endl;
  return value;
}

bool debug_api_t::expect(std::string path, uint32_t expected) {
  int value = peek(path);
  bool ok = value == expected;
  std::cout << "* EXPECT " << path << " -> " << value << " == " << expected;
  if (ok) {
    std::cout << " PASS * " << std::endl;
  } else {
    if (fail_t < 0) fail_t = t;
    std::cout << " FAIL * " << std::endl;
  }
  pass &= ok;
  return ok;
}

bool debug_api_t::expect(bool ok, std::string s) {
  std::cout << "* " << s;
  if (ok) {
    std::cout << " PASS * " << std::endl;
  } else {
    if (fail_t < 0) fail_t = t;
    std::cout << " FAIL * " << std::endl;
  }
  pass &= ok;
  return ok;
} 

uint32_t debug_api_t::rand_next(int limit) {
  return rand() % limit;
}
