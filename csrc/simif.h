#ifndef __SIMIF_H
#define __SIMIF_H

#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <queue>
#include "biguint.h"
#include "sample.h"

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;
typedef std::map< size_t, biguint_t > map_t;
typedef std::queue<biguint_t> trace_t;

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

    // maps 
    idmap_t in_map;
    idmap_t out_map;
    idmap_t in_trace_map;
    idmap_t out_trace_map;
    map_t poke_map;
    map_t peek_map;

    // simulation information
    const std::string prefix;
    const bool log; 
    bool ok;
    uint64_t t;
    uint64_t fail_t;
    
    // trace information
    size_t trace_count;
    std::vector<trace_t> in_traces;
    std::vector<trace_t> out_traces; 

    // sample information
    sample_t* samples[SAMPLE_NUM];
    sample_t* last_sample;
    size_t last_sample_id;
    bool sample_split;

    std::vector<std::string> hargs;
    std::vector<std::string> targs;

  protected:
    std::vector<size_t> in_widths;
    std::vector<size_t> out_widths;

    // channel communication
    virtual void poke_channel(size_t addr, biguint_t data) = 0;
    virtual biguint_t peek_channel(size_t addr) = 0;

    // Simulation APIs
    void poke_port(std::string path, biguint_t value);
    biguint_t peek_port(std::string path);
    bool expect_port(std::string path, biguint_t expected);
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
