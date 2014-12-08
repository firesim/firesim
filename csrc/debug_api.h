#ifndef __DEBUG_API_H
#define __DEBUG_API_H

#include <stdint.h>
#include <string>
#include <vector>
#include <map>

class debug_api_t
{
  public:
    debug_api_t(std::string design): debug_api_t(design, true) {}
    debug_api_t(std::string design, bool trace_);
    ~debug_api_t();
    virtual void run() = 0;

  private:
    void poke(uint64_t value);
    uint64_t peek();
    void poke_steps(size_t n);
    void poke_all();
    void peek_all();
    void poke_snap();
    uint32_t trace_mem();
    void read_snap(char* snap);
    void write_snap(char* snap, size_t n);
    void read_io_map_file(std::string filename);
    void read_chain_map_file(std::string filename);
    void write_replay_file(std::string filename);

    std::map<uint32_t, uint32_t> mem;
    std::map<size_t, uint32_t> poke_map;
    std::map<size_t, uint32_t> peek_map;
    std::map<std::string, std::vector<size_t> > input_map;
    std::map<std::string, std::vector<size_t> > output_map;
    std::vector<std::string> signals;
    std::vector<size_t> widths;
    std::string design;

    size_t hostlen;
    size_t addrlen;
    size_t memlen;
    size_t cmdlen;
    size_t snaplen;
    size_t _step;
    size_t _poke;
    size_t _peek;
    size_t _snap;
    size_t _mem;
    size_t input_num;
    size_t output_num;

    bool trace;
    bool pass;
    int64_t fail_t;
    
    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000;

  protected:
    std::string snapfilename;
    void step(size_t n);
    void poke(std::string path, uint64_t value);
    uint64_t peek(std::string path);
    bool expect(std::string path, uint64_t expected);
    bool expect(bool ok, std::string s);
    void load_mem(std::string filename);
    void write_mem(uint64_t addr, uint64_t data);
    uint64_t read_mem(uint64_t addr);
    uint64_t rand_next(size_t limit); 
    uint64_t t;
};

#endif // __DEBUG_API_H
