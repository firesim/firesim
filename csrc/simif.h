#ifndef __SIMIF_H
#define __SIMIF_H

#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <deque>
#include <queue>
#include <biguint.h>

typedef std::map< std::string, size_t > iomap_t;
typedef std::map< std::string, size_t >::const_iterator iomap_it_t;
typedef std::map< size_t, size_t > wmap_t;
typedef std::map< size_t, biguint_t > map_t;

class simif_t
{
  public:
    simif_t(
      std::vector<std::string> args, 
      std::string prefix = "Top", 
      bool _log = false); 
    ~simif_t();

    virtual int run() = 0;

  private:
    virtual void load_mem(std::string filename) = 0;
    void read_map(std::string filename);

    // maps 
    iomap_t in_map;
    iomap_t out_map;
    map_t poke_map;
    map_t peek_map;

    // simulation information
    const bool log; 
    bool ok;
    uint64_t t;
    uint64_t fail_t;

    std::vector<std::string> hargs;
    std::vector<std::string> targs;

  protected:
    std::string prefix;
    iomap_t req_map;
    iomap_t resp_map;
    wmap_t in_widths;
    wmap_t out_widths;

    // channel communication
    virtual void poke_channel(size_t addr, biguint_t data) = 0;
    virtual biguint_t peek_channel(size_t addr) = 0;
    virtual void write_mem(size_t addr, biguint_t data) = 0;
    virtual biguint_t read_mem(size_t addr) = 0;

    // Simulation APIs
    void poke_port(std::string path, biguint_t value);
    biguint_t peek_port(std::string path);
    bool expect_port(std::string path, biguint_t expected);
    bool expect(bool ok, const char *s);
    void step(size_t n);

    void init();
    uint64_t cycles() { return t; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; } 
};

#endif // __SIMIF_H
