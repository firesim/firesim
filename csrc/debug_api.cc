#include "debug_api.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <assert.h>
#include <iostream>
#include <fstream>
#include <sstream>

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

#define OPWIDTH 6
#define STEP 0
#define POKE 1
#define PEEK 2
#define SNAP 3

debug_api_t::debug_api_t(std::string design)
  : t(0), pass(true)
{
  int fd = open("/dev/mem", O_RDWR|O_SYNC);
  assert(fd != -1);

  int host_prot = PROT_READ | PROT_WRITE;
  int flags = MAP_SHARED;
  uintptr_t pgsize = sysconf(_SC_PAGESIZE);
  assert(dev_paddr % pgsize == 0);

  dev_vaddr = (uintptr_t*)mmap(0, pgsize, host_prot, flags, fd, dev_paddr);
  assert(dev_vaddr != MAP_FAILED);
  write_reg(31, 0); // reset
  __sync_synchronize();

  read_io_map_file(design + ".io.map");
  read_chain_map_file(design + ".chain.map");
}

debug_api_t::~debug_api_t() {
  std::cout << t << " Cycles";
  if (pass) 
    std::cout << " Passed" << std::endl;
  else 
    std::cout << " Failed" << std::endl;
}

void debug_api_t::read_io_map_file(std::string filename) {
  std::ifstream file(filename.c_str());
  std::string line;
  bool isInput = false;
  if (file.is_open()) {
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
  if (file.is_open()) {
     std::istringstream iss(line);
     std::string path;
     int width;
     iss >> path >> width;
     chain_names.push_back(path);
     chain_widths.push_back(width);
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
}

void debug_api_t::peek_all() {
  write_reg(0, PEEK);
  __sync_synchronize();

  for (int i = 0 ; i < output_map.size() ; i++) {
    __sync_synchronize();
    peek_map[i] = (uint32_t) read_reg(1);
  }
}

void debug_api_t::poke_steps(uint32_t n) {
  write_reg(0, n << OPWIDTH | STEP);
  __sync_synchronize();
}

void debug_api_t::step(uint32_t n) {
  poke_all();
  poke_steps(n);
  t += n;
  std::cout << "* STEP " << n << " -> " << t << " * " << std::endl;
  peek_all();
}

void debug_api_t::poke(std::string path, uint32_t value) {
  assert(input_map.find(path) != input_map.end());
  std::cout << "* POKE " << path << " <- " << value << " * " << std::endl;
  poke_map[input_map[path]] = value;
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
  std::cout << "* EXPECT " << path << " -> " << value << " == " << expected << " * " << std::endl;
  pass &= ok;
  return ok;
}
