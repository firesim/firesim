#ifndef __DEBUG_API_H
#define __DEBUG_API_H

#include <stdint.h>
#include <string>
#include <vector>
#include <map>
#include <sstream>

class debug_api_t
{
  public:
    debug_api_t(std::string design): debug_api_t(design, false) {}
    debug_api_t(std::string design, bool trace_);
    ~debug_api_t();
    virtual void run() = 0;

  private:
    void poke_steps(uint32_t n);
    void poke(uint64_t value);
    uint64_t peek();
    void poke_all();
    void peek_all();
    void poke_snap();
    void read_snap(std::string& snap);
    void write_snap(std::string& snap, uint32_t n);
    void read_io_map_file(std::string filename);
    void read_chain_map_file(std::string filename);
    void write_replay_file(std::string filename);

    std::map<int, uint32_t> poke_map;
    std::map<int, uint32_t> peek_map;
    std::map<std::string, std::vector<int> > input_map;
    std::map<std::string, std::vector<int> > output_map;
    std::vector<std::string> outputs;
    std::vector<std::string> signals;
    std::vector<int> widths;
    std::string design;
    std::ostringstream replay;

    int hostlen;
    int addrlen;
    int memlen;
    int cmdlen;
    int STEP;
    int POKE;
    int PEEK;
    int SNAP;
    int MEM;
    int input_num;
    int output_num;

    bool trace;
    bool pass;
    int64_t fail_t;
    uint64_t snap_size;
    
    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000;

  protected:
    std::string replayfile;
    void step(uint32_t n);
    void poke(std::string path, uint64_t value);
    uint64_t peek(std::string path);
    bool expect(std::string path, uint64_t expected);
    bool expect(bool ok, std::string s);
    void write_mem(uint64_t addr, uint64_t data);
    uint64_t read_mem(uint64_t addr);
    uint64_t rand_next(int limit); 
    uint64_t t;
};

#endif // __DEBUG_API_H
