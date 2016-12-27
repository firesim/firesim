#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
#include <queue>
#ifndef _WIN32
#include <sys/time.h>
#else
#include <time.h>
#endif
#include "biguint.h"
#include "sample.h"

static inline uint64_t timestamp() {
#ifndef _WIN32
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
#else
  return (uint64_t) time(NULL);
#endif
}

static inline double diff_secs(uint64_t end, uint64_t start) {
#ifndef _WIN32
  return ((double)(end - start)) / 1000000.0;
#else
  return difftime((time_t)end, (time_t)start);
#endif
}

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

class simif_t
{
  public:
    simif_t();
    virtual ~simif_t() { }
  private:
    // simulation information
    bool log;
    bool pass;
    uint64_t t;
    uint64_t fail_t;
    time_t seed; 
    size_t tracelen;
    virtual void load_mem(std::string filename);
    inline void take_steps(size_t n, bool blocking) {
      write(MASTER(STEP), n);
      if (blocking) while(!done());
    }

  public:
    // Simulation APIs
    virtual void init(int argc, char** argv, bool log = false);
    virtual int finish();
    virtual void step(int n, bool blocking = true);
    inline bool done() { return read(MASTER(DONE)); }

    // Widget communication
    virtual void write(size_t addr, uint32_t data) = 0;
    virtual uint32_t read(size_t addr) = 0;


    inline void poke(size_t id, uint32_t value) { 
      if (log) fprintf(stderr, "* POKE %s.%s <- 0x%x *\n", TARGET_NAME, INPUT_NAMES[id], value);
      write(INPUT_ADDRS[id], value);
    }

    inline uint32_t peek(size_t id) {
      uint32_t value = read(OUTPUT_ADDRS[id]); 
      if (log) fprintf(stderr, "* PEEK %s.%s -> 0x%x *\n", TARGET_NAME, OUTPUT_NAMES[id], value);
      return value;
    }

    inline void poke(size_t id, biguint_t& value) {
      if (log) fprintf(stderr, "* POKE %s.%s <- 0x%s *\n", TARGET_NAME, INPUT_NAMES[id], value.str().c_str());
      for (size_t off = 0 ; off < INPUT_CHUNKS[id] ; off++) {
        write(INPUT_ADDRS[id]+off, value[off]);
      }
    }

    inline void peek(size_t id, biguint_t& value) {
      uint32_t buf[16];
      for (size_t off = 0; off < OUTPUT_CHUNKS[id]; off++) {
        buf[off] = read(OUTPUT_ADDRS[id] + off);
      }
      value = biguint_t(buf, OUTPUT_CHUNKS[id]);
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

    // A default reset scheme that pulses the global chisel reset
    void target_reset(int pulse_start = 1, int pulse_length = 5);

    inline void read_mem(size_t addr, biguint_t& data) {
#ifdef LOADMEM
      write(LOADMEM_R_ADDRESS, addr);
      uint32_t d[MEM_DATA_CHUNK];
      for (size_t off = 0 ; off < MEM_DATA_CHUNK; off++) {
        d[off] = read(LOADMEM_R_DATA);
      }
      data = biguint_t(d, MEM_DATA_CHUNK);
#endif
    }

    inline void write_mem(size_t addr, biguint_t& data) {
#ifdef LOADMEM
      write(LOADMEM_W_ADDRESS, addr);
      for (size_t off = 0; off < MEM_DATA_CHUNK; off++) {
        write(LOADMEM_W_DATA, data[off]);
      }
#endif
    }
    
    inline uint64_t cycles() { return t; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; }

    inline void set_tracelen(size_t len) {
      assert(len > 2);
      tracelen = len;
#ifdef ENABLE_SNAPSHOT
      write(TRACELEN_ADDR, len);
#endif
    }
    inline size_t get_tracelen() { return tracelen; }

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
