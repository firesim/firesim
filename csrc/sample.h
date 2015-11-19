#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <array>
#include <vector>
#include <ostream>
#include "biguint.h"

enum SAMPLE_INST_TYPE { CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT };

class sample_inst_t { 
public:
  virtual ~sample_inst_t() {} 
  virtual void dump(FILE *file) const = 0;  
  virtual std::ostream& dump(std::ostream &os) const = 0;  
  friend std::ostream& operator<<(std::ostream &os, const sample_inst_t& cmd) {
    return cmd.dump(os);
  }
};

class step_t: public sample_inst_t {
public:
  step_t(int n_): n(n_) { }
  void dump(FILE *file) const {
    fprintf(file, "%u %u\n", STEP, n);
  }
  std::ostream& dump(std::ostream &os) const {
    return os << STEP << " " << n << std::endl;
  }
private:
  const size_t n;
};

class load_t: public sample_inst_t {
public:
  load_t(std::string& node_, biguint_t* value_, int idx_ = -1): 
    node(node_.c_str()), value(value_), idx(idx_) { }
  ~load_t() { delete value; }
  void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", LOAD, node, (value->str()).c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << LOAD << " " << node << " " << *value << " " << idx << std::endl;
  }
private:
  const char* node;
  biguint_t* const value;
  const int idx;
};

class force_t: public sample_inst_t {
public:
  force_t(std::string& node_, biguint_t* value_):
    node(node_.c_str()), value(value_) { }
  ~force_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", FORCE, node, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << FORCE << " " << node << " " << *value << std::endl;
  }
private:
  const char* node;
  biguint_t* const value;
};

class poke_t: public sample_inst_t {
public:
  poke_t(std::string& node_, biguint_t* value_): 
    node(node_.c_str()), value(value_) {} 
  ~poke_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", POKE, node, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << POKE << " " << node << " " << *value << std::endl;
  } 
private:
  const char* node;
  biguint_t* const value;
};

class expect_t: public sample_inst_t {
public:
  expect_t(std::string& node_, biguint_t* value_): 
    node(node_.c_str()), value(value_) { }
  ~expect_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", EXPECT, node, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << EXPECT << " " << node << " " << *value << std::endl;
  }
private:
  const char* node;
  biguint_t* const value;
};

class count_t: public sample_inst_t {
public:
  count_t(std::string& node_, biguint_t* value_): 
    node(node_.c_str()), value(value_) { }
  ~count_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %u\n", COUNT, node, value->uint());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << COUNT << " " << node << " " << *value << std::endl;
  }
private:
  const char* node;
  biguint_t* const value;
};

class sample_t {
public:
  sample_t(const char* snap, uint64_t _cycle);
  sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle);
  virtual ~sample_t();

  size_t read_chain(CHAIN_TYPE type, const char* snap, size_t start = 0);
  void add_cmd(sample_inst_t *cmd) { cmds.push_back(cmd); }

  virtual void dump(FILE *file) const {
    fprintf(file, "%u cycle: %llu\n", POKE, cycle);
    for (size_t i = 0 ; i < cmds.size() ; i++) {
      cmds[i]->dump(file);
    }
  }

  std::ostream& dump(std::ostream &os) const {
    os << CYCLE << " cycle: " << cycle << std::endl;
    for (size_t i = 0 ; i < cmds.size() ; i++) {
      os << *cmds[i];
    }
    return os;
  }

  friend std::ostream& operator<<(std::ostream& os, const sample_t& s) {
    return s.dump(os);
  }

  static void init_chains();
  static void add_to_chains(CHAIN_TYPE, std::string&, size_t, int index = -1);

private:
  const uint64_t cycle;
  std::vector<sample_inst_t*> cmds;
  static std::array<std::vector<std::string>, CHAIN_NUM> signals; 
  static std::array<std::vector<size_t>,      CHAIN_NUM> widths; 
  static std::array<std::vector<ssize_t>,     CHAIN_NUM> indices;
};

#endif // __SAMPLE_H
