#include "debug_api.h"

class Router_t: debug_api_t
{
public:
  Router_t(int n_): debug_api_t("Router"), n(n_) {}
  void run() {
    rd(0, 0);
    wr(0, 1);
    rd(0, 1);
    rt(0, 1);
  }
private:
  void rd(uint64_t addr, uint64_t data) {
    poke("Router.io_in_valid",      0);
    poke("Router.io_writes_valid",  0);
    poke("Router.io_reads_valid",   1);
    poke("Router.io_replies_ready", 1);
    poke("Router.io_reads_bits_addr", addr);
    step(1);
    expect("Router.io_replies_bits", data);
  }
  void wr(uint64_t addr, uint64_t data) {
    poke("Router.io_in_valid",     0);
    poke("Router.io_reads_valid",  0);
    poke("Router.io_writes_valid", 1);
    poke("Router.io_writes_bits_addr", addr);
    poke("Router.io_writes_bits_data", data);
    step(1);
  }
  bool isAnyValidOuts() {
    for (int i = 0 ; i < n ; i++) {
      std::ostringstream path;
      path <<  "Router.io_outs_" << i << "_valid";
      if (peek(path.str()) == 1) return true;  
    }
    return false;
  }
  void rt(uint64_t header, uint64_t body) {
    for (int i = 0 ; i < n ; i++) {
      std::ostringstream path;
      path <<  "Router.io_outs_" << i << "_ready";
      poke(path.str(), 1);
    }
    poke("Router.io_reads_valid",  0);
    poke("Router.io_writes_valid", 0);
    poke("Router.io_in_valid",     1);
    poke("Router.io_in_bits_header", header);
    poke("Router.io_in_bits_body",   body);
    int i = 0;
    do {
      step(1);
      i += 1;
    } while (!isAnyValidOuts() && i < 10);
    expect(i < 10, "FIND VALID OUT");
  }
  int n;
};

int main() 
{
  Router_t Router(4);
  Router.run();
  return 0;
}
