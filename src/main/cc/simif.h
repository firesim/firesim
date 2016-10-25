#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
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
    simif_t();
    virtual ~simif_t() { }
  private:
    virtual void load_mem(std::string filename);

    // simulation information
    bool log;
    bool pass;
    uint64_t t;
    uint64_t fail_t;
    time_t seed; 
    size_t trace_len;

    // maps 
    uint32_t poke_map[POKE_SIZE];
    uint32_t peek_map[PEEK_SIZE];

  protected:
    // channel communication
    virtual void write(size_t addr, uint32_t data) = 0;
    virtual uint32_t read(size_t addr) = 0;

  public:
    // Simulation APIs
    virtual void init(int argc, char** argv, bool log = false, bool fast_loadmem = false);
    virtual int finish();
    void step(size_t n);

    inline void poke(size_t id, uint32_t value) { 
      if (log) fprintf(stderr, "* POKE %s.%s <- 0x%x *\n", TARGET_NAME, INPUT_NAMES[id], value);
      poke_map[id] = value;
    }

    inline uint32_t peek(size_t id) {
      uint32_t value = peek_map[id]; 
      if (log) fprintf(stderr, "* PEEK %s.%s -> 0x%x *\n", TARGET_NAME, OUTPUT_NAMES[id], value);
      return value;
    }

    inline void poke(size_t id, biguint_t& value) {
      if (log) fprintf(stderr, "* POKE %s.%s <- 0x%s *\n", TARGET_NAME, INPUT_NAMES[id], value.str().c_str());
      for (size_t off = 0 ; off < INPUT_CHUNKS[id] ; off++) {
        poke_map[id+off] = value[off];
      }
    }

    inline void peek(size_t id, biguint_t& value) {
      value = biguint_t(peek_map+id, OUTPUT_CHUNKS[id]);
      if (log) fprintf(stderr, "* PEEK %s.%s -> 0x%s *\n", TARGET_NAME, OUTPUT_NAMES[id], value.str().c_str());
    }

    inline bool expect(size_t id, uint32_t expected) {
      uint32_t value = peek(id);
      bool pass = value == expected;
      if (log) fprintf(stderr, "* EXPECT %s.%s -> 0x%x ?= 0x%x : %s\n",
        TARGET_NAME, OUTPUT_NAMES[id], value, expected, pass ? "PASS" : "FAIL");
      return expect(pass, NULL);
    }

    inline bool expect(size_t id, biguint_t& expected) {
      biguint_t value;
      peek(id, value);
      bool pass = value == expected;
      if (log) fprintf(stderr, "* EXPECT %s.%s -> 0x%s ?= 0x%s : %s\n",
        TARGET_NAME, OUTPUT_NAMES[id], value.str().c_str(), expected.str().c_str(), pass ? "PASS" : "FAIL");
      return expect(pass, NULL);
    }

    inline bool expect(bool pass, const char *s) {
      if (log && s) fprintf(stderr, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
      if (this->pass && !pass) fail_t = t;
      this->pass &= pass;
      return pass;
    }

    inline biguint_t read_mem(size_t addr) {
      write(LOADMEM_R_ADDRESS, addr);
      uint32_t d[MEM_DATA_CHUNK];
      for (size_t off = 0 ; off < MEM_DATA_CHUNK; off++) {
        d[off] = read(LOADMEM_R_DATA);
      }
      return biguint_t(d, MEM_DATA_CHUNK);
    }

    inline void write_mem(size_t addr, biguint_t& data) {
      write(LOADMEM_W_ADDRESS, addr);
      for (size_t off = 0; off < MEM_DATA_CHUNK; off++) {
        write(LOADMEM_W_DATA, data[off]);
      }
    }
    
    inline uint64_t cycles() { return t; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; }

    inline void set_tracelen(size_t len) {
      assert(len > 2);
      trace_len = len;
#ifdef ENABLE_SNAPSHOT
      write(EMULATIONMASTER_TRACELEN, len);
#endif
    }
    inline size_t get_tracelen() { return trace_len; }

#ifdef ENABLE_SNAPSHOT
  private:
    // sample information
    sample_t** samples;
    sample_t* last_sample;
    size_t sample_num;
    size_t last_sample_id;
    std::string sample_file;

    size_t trace_count;

    // profile information
    bool profile;
    size_t sample_count;
    uint64_t sample_time;
    uint64_t sim_start_time;

  protected:
    sample_t* read_snapshot();
    sample_t* read_traces(sample_t* s);
#endif
};

#endif // __SIMIF_H
