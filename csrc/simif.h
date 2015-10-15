#ifndef __SIMIF_H
#define __SIMIF_H

#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <queue>
#include "biguint.h"
#include "sample.h"

#include <sys/time.h>
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
    ~simif_t();

    virtual int run() = 0;

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
    time_t seed; 

    // maps 
    idmap_t in_map;
    idmap_t out_map;
    idmap_t in_trace_map;
    idmap_t out_trace_map;
    uint32_t* const poke_map;
    uint32_t* const peek_map;

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

    void poke_port(size_t id, biguint_t data) {
      for (size_t off = 0 ; off < in_chunks[id] ; off++) {
        uint64_t value = (data >> (off << CHANNEL_OFFSET)).uint();
        poke_channel(id+off, value);
      }
    }
    biguint_t peek_port(size_t id) {
      biguint_t data = 0;
      for (size_t off = 0 ; off < out_chunks[id] ; off++) {
        uint64_t value = peek_channel(id+off);
        data |= biguint_t(value) << (off << CHANNEL_OFFSET);
      }
      return data;
    }

  protected:
    // channel communication
    virtual void poke_channel(size_t addr, uint64_t data) = 0;
    virtual uint64_t peek_channel(size_t addr) = 0;

    virtual void send_tokens(uint32_t* const map, size_t size, size_t off) = 0;
    virtual void recv_tokens(uint32_t* const map, size_t size, size_t off) = 0;

    // Simulation APIs
    size_t get_in_id(std::string path);
    size_t get_out_id(std::string path);
    void poke_port(size_t id, uint32_t value) { poke_map[id] = value; }
    uint32_t& peek_port(size_t id, uint32_t &value) { return value = peek_map[id]; }
    void poke_port(std::string path, uint32_t value);
    uint32_t& peek_port(std::string path, uint32_t &value);
    bool expect_port(std::string path, uint32_t expected);
    bool expect(bool ok, const char *s);
    void step(size_t n);
    virtual void write_mem(size_t addr, biguint_t data);
    virtual biguint_t read_mem(size_t addr);
    sample_t* trace_ports(sample_t* s);
    std::string read_snapshot();
    
    void init();
    void finish();
    uint64_t cycles() { return t; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; } 
};

#endif // __SIMIF_H
