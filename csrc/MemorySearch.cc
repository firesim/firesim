#include "debug_api.h"

class MemorySearch_t: debug_api_t
{
public:
  MemorySearch_t(): debug_api_t("MemorySearch") {}
  void run() {
    std::vector<uint32_t> list;
    list.push_back(0);
    list.push_back(4);
    list.push_back(15);
    list.push_back(14);
    list.push_back(2);
    list.push_back(5);
    list.push_back(13);
    int n = 8;
    int maxT = n * (list.size() + 3);
    for (int i = 0 ; i < n ; i++) {
      uint32_t target = rand_next(16);
      poke("MemorySearch.io_en", 1);
      poke("MemorySearch.io_target", target);
      step(1);
      poke("MemorySearch.io_en", 0);
      do {
        step(1);
      } while(peek("MemorySearch.io_done") == 0 && t < maxT);
      uint32_t addr = peek("MemorySearch.io_address");
      std::ostringstream oss;
      oss << "LOOKING FOR " << target  << " FOUND " << addr;
      expect(addr == list.size() | list[addr] == target, oss.str());
    } 
  }
};

int main() 
{
  MemorySearch_t MemorySearch;
  MemorySearch.run();
  return 0;
}
