#ifndef __SIMIF_H
#define __SIMIF_H

#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <deque>
#include <queue>
#include "sample.h"

typedef std::map< size_t, uint64_t > addr_t;
typedef std::map< size_t, uint32_t > map_t;
typedef std::map< size_t, std::queue<uint32_t> > qmap_t;
typedef std::map< std::string, std::vector<size_t> > iomap_t;

// CONSTANTS
enum DEBUG_CMD {
  STEP_CMD, POKE_CMD, PEEK_CMD, POKEQ_CMD, PEEKQ_CMD, TRACE_CMD, MEM_CMD,
};

enum STEP_RESP {
  RESP_FIN, RESP_TRACE, RESP_PEEKQ,
};

// NOTE: CONTANTS FROM DESIGN PARAMETERS
// HOST_LEN
// CMD_LEN
// MIF_DATA_BITS
// MIF_ADDR_BITS
// MIF_TAG_BITS
// TRACE_LEN

class simif_t
{
  public:
    simif_t(
      std::vector<std::string> args, 
      std::string prefix = "Top", 
      bool _log = false, 
      bool _sample_check = false);
    ~simif_t();

    virtual int run() = 0;

  private:
    // atomic operations
    virtual void poke_host(uint32_t value) = 0;
    virtual bool peek_host_ready() = 0;
    virtual uint32_t peek_host() = 0;

    // read the target's information
    void read_io_map(std::string filename);
    void read_chain_map(std::string filename);

    // simif operation
    void poke_steps(size_t n, bool read_next = true);
    void poke_all();
    void peek_all();
    void pokeq_all();
    void peekq_all();
    void peek_trace();
    void trace_qout();
    void trace_mem();
    void record_io(size_t r, size_t n);
    void do_sampling(size_t index, size_t r, size_t last_r);
    std::string read_snap();

    // io information
    iomap_t qin_map;
    iomap_t qout_map;
    iomap_t win_map;
    iomap_t wout_map;
    size_t qin_num;
    size_t qout_num;
    size_t win_num;
    size_t wout_num;

    // poke & peek mappings
    map_t poke_map;
    map_t peek_map;
    qmap_t pokeq_map;
    qmap_t peekq_map;

    // memory trace
    mem_t mem_writes;
    addr_t mem_reads;

    // simulation information
    const bool log; 
    const bool sample_check;
    bool pass;
    bool is_done;
    int exitcode;   
    uint64_t t;
    uint64_t fail_t;
    uint64_t snap_len;
    uint64_t max_cycles;

    std::vector<sample_t*> samples;

    std::vector<std::string> hargs;
    std::vector<std::string> targs;

  protected:
    std::string prefix;
    std::string loadmem;
    size_t sample_num;
    size_t step_size;

    // Simulation APIs
    void step(size_t n);
    void poke(std::string path, biguint_t& value);
    void pokeq(std::string path, biguint_t& value);
    void poke(std::string path, uint64_t value) {
      biguint_t num = value;
      poke(path, num);
    }
    void pokeq(std::string path, uint64_t value) {
      biguint_t num = value;
      pokeq(path, num);
    }
    biguint_t peek(std::string path);
    biguint_t peekq(std::string path);
    bool peekq_valid(std::string);

    bool expect(std::string path, biguint_t& expected);
    bool expect(std::string path, uint64_t expected) {
      biguint_t num = expected;
      expect(path, num);
    }
    bool expect(bool ok, const char *s);

    virtual void load_mem();
    virtual void write_mem(uint64_t addr, biguint_t &data);
    virtual biguint_t read_mem(uint64_t addr);

    uint64_t cycles() { return t; }
    bool timeout() { return t >= max_cycles; }

    uint64_t rand_next(uint64_t limit) { return rand() % limit; } 
};

#endif // __SIMIF_H
