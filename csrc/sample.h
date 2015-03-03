#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <vector>
#include <ostream>
#include <map>
#include "biguint.h"

enum SAMPLE_CMD {
  FIN, STEP, POKE, EXPECT, READ, WRITE
};

class sample_inst_t { 
public:
  virtual ~sample_inst_t() {} 
  virtual std::ostream& dump(std::ostream &os) const = 0;  
  friend std::ostream& operator<<(std::ostream &os, const sample_inst_t& cmd) {
    return cmd.dump(os);
  }
};

class step_t: public sample_inst_t {
public:
  step_t(int n_): n(n_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << STEP << " " << n << std::endl;
  }
private:
  const size_t n;
};

class poke_t: public sample_inst_t {
public:
  poke_t(std::string& node_, biguint_t &value_): node(node_.c_str()), value(value_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << POKE << " " << node << " " << std::hex << value << std::dec << std::endl;
  } 
private:
  const char* node;
  const biguint_t value;
};

class expect_t: public sample_inst_t {
public:
  expect_t(std::string& node_, biguint_t &value_): node(node_.c_str()), value(value_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << EXPECT << " " << node << " " << std::hex << value << std::dec << std::endl;
  }
private:
  const char* node;
  const biguint_t value;
};

class read_t: public sample_inst_t {
public:
  read_t(uint64_t addr_, size_t tag_): addr(addr_), tag(tag_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << READ << std::hex << " " << addr << " " << tag << std::dec << std::endl; 
  } 
private:
  const uint64_t addr;
  const size_t tag;
};

typedef std::map< uint64_t, biguint_t > mem_t;

class simif_t;
class sample_t {
public:
  sample_t(size_t t_, std::string& snap);
  ~sample_t(); 

  sample_t& operator+=(const sample_inst_t *cmd);
  sample_t& operator+=(const sample_t& that);

  void add_cmd(sample_inst_t *cmd) {
    cmds.push_back(cmd);
  }

  void add_mem(uint64_t addr, biguint_t &data) {
    mem[addr] = data;
  }

  static void add_mapping(std::string signal, size_t width) {
    signals.push_back(signal);
    widths.push_back(width);
  }

  static void dump(std::ostream &os, std::vector<sample_t*> &samples);

  friend bool sample_cmp(sample_t*, sample_t*);
  friend std::ostream& operator<<(std::ostream&, const sample_t&);
  friend simif_t;
private:
  const size_t t;
  std::vector<sample_inst_t*> cmds;
  mem_t mem;
  sample_t *prev = NULL;
  sample_t *next = NULL;
  static std::vector<std::string> signals;
  static std::vector<size_t> widths;
};

#endif // __SAMPLE_H
