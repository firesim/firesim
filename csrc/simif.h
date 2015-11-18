#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <queue>
#include <sys/time.h>
#include "biguint.h"
#include "sample.h"

static inline uint64_t timestamp() {
  struct timeval tv;
  gettimeofday(&tv,NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

class simif_t
{
  public:
    simif_t(std::vector<std::string> args, std::string prefix, bool _log = false); 
    virtual ~simif_t();

  private:
    void read_map(std::string filename);
    void read_chain(std::string filename);
    virtual void load_mem(std::string filename);

    // simulation information
    const std::string prefix;
    const bool log; 
    bool ok;
    uint64_t t;
    uint64_t fail_t;
    size_t trace_count;
    size_t trace_len;
    time_t seed; 

    // maps 
    idmap_t in_map;
    idmap_t out_map;
    idmap_t in_tr_map;
    idmap_t out_tr_map;
    uint32_t poke_map[POKE_SIZE];
    uint32_t peek_map[PEEK_SIZE];

    // sample information
    sample_t* samples[SAMPLE_NUM];
    sample_t* last_sample;
    size_t last_sample_id;
    bool sample_split;

    // profile information    
    bool profile;
    uint64_t sample_time;
    uint64_t sim_start_time;
    std::vector<std::string> hargs;
    std::vector<std::string> targs;

    std::map<size_t, size_t> in_chunks;
    std::map<size_t, size_t> out_chunks;

  protected:
    // channel communication
    virtual void poke_channel(size_t addr, uint32_t data) = 0;
    virtual uint32_t peek_channel(size_t addr) = 0;

    // Simulation APIs
    inline size_t get_in_id(std::string path) {
      assert(in_map.find(path) != in_map.end()); 
      return in_map[path];
    }

    inline size_t get_out_id(std::string path) {
      assert(out_map.find(path) != out_map.end()); 
      return out_map[path];
    }

    inline void poke_port(size_t id, uint32_t value) { 
      poke_map[id-1] = value; 
    }

    inline uint32_t peek_port(size_t id) {
      return peek_map[id-1]; 
    }

    inline void poke_port(std::string path, uint32_t value) {
      if (log) fprintf(stdout, "* POKE %s <- %x *\n", path.c_str(), value);
      poke_port(get_in_id(path), value);
    }

    inline uint32_t peek_port(std::string path) {
      uint32_t value = peek_port(get_out_id(path));
      if (log) fprintf(stdout, "* PEEK %s <- %x *\n", path.c_str(), value);
      return value;
    }

    inline void poke_port(size_t id, biguint_t& data) {
      for (size_t off = 0 ; off < in_chunks[id] ; off++) {
        poke_map[id-1+off] = data[off];
      }
    }

    inline void peek_port(size_t id, biguint_t& data) {
      data = biguint_t(peek_map+id-1, out_chunks[id]);
    }

    inline void poke_port(std::string path, biguint_t &value) {
      if (log) fprintf(stdout, "* POKE %s <- %s *\n", path.c_str(), value.str().c_str());
      poke_port(get_in_id(path), value);
    }

    inline void peek_port(std::string path, biguint_t &value) {
      peek_port(get_out_id(path), value); 
      if (log) fprintf(stdout, "* PEEK %s <- %s *\n", path.c_str(), value.str().c_str());
    }

    inline bool expect_port(std::string path, uint32_t expected) {
      uint32_t value = peek_port(path);
      bool pass = value == expected;
      std::ostringstream oss;
      if (log) oss << "EXPECT " << path << " " << value << " == " << expected;
      return expect(pass, oss.str().c_str());
    }

    inline bool expect_port(std::string path, biguint_t& expected) {
      biguint_t value;
      peek_port(path, value); 
      bool pass = value == expected;
      std::ostringstream oss;
      if (log) oss << "EXPECT " << path << " " << value << " == " << expected;
      return expect(pass, oss.str().c_str());
    }

    bool expect(bool pass, const char *s);
    void step(size_t n);
    virtual void read_mem(size_t addr, biguint_t data[]);
    virtual void write_mem(size_t addr, biguint_t data[]);
    sample_t* trace_ports(sample_t* s);
    sample_t* read_snapshot();
    sample_t* read_counters();
    
    void init();
    void finish();
    inline uint64_t cycles() { return t; }
    inline void set_trace_len(size_t len) { 
      trace_len = len;
      poke_channel(TRACE_LEN_ADDR, len);
    }
    inline void set_mem_cycles(size_t cycles) { 
      poke_channel(MEM_CYCLE_ADDR, cycles);
    }
    inline size_t get_trace_len() { return trace_len; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; } 
};

#endif // __SIMIF_H
