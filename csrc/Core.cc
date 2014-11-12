#include "debug_api.h"
#include <fstream>
#include <iostream>
#include <stdlib.h>

class Core_t: debug_api_t
{
public:
  Core_t(int argc, char** argv): debug_api_t("Core") {
    for (int i = 0 ; i < argc ; i++) {
      std::string arg = argv[i];
      if (arg.substr(0, 12) == "+max-cycles=") {
        timeout = atoll(argv[i]+12);
      } else if (arg.substr(0, 9) == "+loadmem=") {
        filename = argv[i]+9;
      }
    }
    testname = filename.substr(filename.rfind("/")+1);
    replayfile = testname + ".replay";

    load_mem();
  }
  void run() {
    poke("Core.io_stall", 1);
    step(1);
    poke("Core.io_stall", 0);
    do {
      uint64_t iaddr = peek("Core.io_icache_addr");
      uint64_t daddr = (peek("Core.io_dcache_addr") >> 2) << 2;
      uint64_t data  = peek("Core.io_dcache_din");
      uint64_t dwe   = peek("Core.io_dcache_we");
      bool ire = peek("Core.io_icache_re") == 1;
      bool dre = peek("Core.io_dcache_re") == 1;

      step(1);

      if (dwe > 1) {
        write(daddr, data, dwe);
      } else if (ire) {
        uint64_t inst = read(iaddr);
        poke("Core.io_icache_dout", inst);
      } else if (dre) {
        uint64_t data = read(daddr);
        poke("Core.io_dcache_dout", data);
      }
    } while (peek("Core.io_host_tohost") == 0 && t < timeout);
    uint64_t tohost = peek("Core.io_host_tohost");
    std::ostringstream reason;
    std::ostringstream result;
    if (t > timeout) {
      reason << "timeout";
      result << "FAILED";
    } else if (tohost != 1) {
      reason << "tohost = " << tohost;
      result << "FAILED";
    } else {
      reason << "tohost = " << tohost;
      result <<"PASSED";
    }
    std::cout << "ISA: " << testname << std::endl;
    std::cout << "*** " << result.str() << " *** (" << reason.str() << ") ";
    std::cout << "after " << t << " simulation cycles" << std::endl;
  }
private:
  std::map<uint64_t, uint64_t> mem;
  std::string testname;
  std::string filename;
  uint64_t timeout;

  void load_mem() {
    std::ifstream in(filename.c_str());
    if (!in) {
      std::cerr << "could not open " << filename << std::endl;
      exit(-1);
    }

    std::string line;
    int i = 0;
    while (std::getline(in, line)) {
      #define parse_nibble(c) ((c) >= 'a' ? (c)-'a'+10 : (c)-'0')
      uint64_t base = (i * line.length()) / 2;
      uint64_t offset = 0;
      for (int k = line.length() - 2 ; k >= 0 ; k -= 2) {
        uint64_t addr = base + offset;
        uint64_t data = (parse_nibble(line[k]) << 4) | parse_nibble(line[k+1]);
        mem[addr] = data;
        offset += 1;
      }
      i += 1;
    }
  }

  uint64_t read(uint64_t addr) {
    uint64_t data = 0;
    for (int i = 0 ; i < 4 ; i++) {
      data |= mem[addr+i] << (8*i);
    }
    return data;
  }

  void write(uint64_t addr, uint64_t data, uint64_t mask) {
    for (int i = 3 ; i >= 0 ; i--) {
      if (((mask >> i) & 1) > 0) {
        mem[addr+i] = (data >> (8*i)) & 0xff;
      }
    }
  }
};

int main(int argc, char** argv) {
  Core_t Core(argc, argv);
  Core.run();
  return 0;
}
