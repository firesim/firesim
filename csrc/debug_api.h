#ifndef __DEBUG_API_H
#define __DEBUG_API_H

#include <stdint.h>
#include <string>
#include <vector>
#include <map>

class debug_api_t
{
  public:
    debug_api_t(std::string design);
    ~debug_api_t();
    void step(uint32_t n);
    // void snap(uintptr_t addr);
    void poke(std::string path, uint32_t value);
    uint32_t peek(std::string path);
    bool expect(std::string path, uint32_t expected);
    uint32_t cycle() { return t; }

  private:
    void poke_all();
    void peek_all();
    void poke_steps(uint32_t n);
    void read_io_map_file(std::string filename);
    void read_chain_map_file(std::string filename);
    std::map<int, uint32_t> poke_map;
    std::map<int, uint32_t> peek_map;
    std::map<std::string, int> input_map;
    std::map<std::string, int> output_map;
    std::vector<std::string> chain_names;
    std::vector<int> chain_widths;

    uint64_t t;
    bool pass;
    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000;
};

#endif // __DEBUG_API_H
