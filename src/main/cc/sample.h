#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <array>
#include <vector>
#include <map>
#include <ostream>
#include <cassert>
#include <inttypes.h>
#include "biguint.h"

enum SAMPLE_INST_TYPE { CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT };
enum { IN_TR = CHAIN_NUM, OUT_TR };

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

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
  step_t(size_t n_): n(n_) { }
  void dump(FILE *file) const {
    fprintf(file, "%u %zu\n", STEP, n);
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
    fprintf(file, "%u %s %s %d\n", LOAD, node, (value->str()).c_str(), idx);
  }
  std::ostream& dump(std::ostream &os) const {
    return os << LOAD << " " << node << " " << *value << " " << idx << std::endl;
  }
private:
  const char* const node;
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
  inline const char* name() const { return node; }
private:
  const char* const node;
  biguint_t* const value;
};


void dump_f(
  FILE *file, SAMPLE_INST_TYPE type, const char* node, uint32_t* value, size_t size);
std::ostream& dump_s(
  std::ostream &os, SAMPLE_INST_TYPE type, const char* node, uint32_t* value, size_t size);

class poke_t: public sample_inst_t {
public:
  poke_t(const std::string &node_, uint32_t* value_, size_t size_);
  ~poke_t() { delete value; }
  virtual void dump(FILE *file) const {
    dump_f(file, POKE, node, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, POKE, node, value, size);
  } 
private:
  const char* const node;
  const size_t size;
  uint32_t* value;
};

class expect_t: public sample_inst_t {
public:
  expect_t(const std::string& node_, uint32_t* value_, size_t size_); 
  ~expect_t() { delete value; }
  virtual void dump(FILE *file) const {
    dump_f(file, EXPECT, node, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, EXPECT, node, value, size);
  }
private:
  const char* const node;
  const size_t size;
  uint32_t* value;
};

class count_t: public sample_inst_t {
public:
  count_t(std::string& node_, biguint_t* value_): 
    node(node_.c_str()), value(value_) { }
  ~count_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %" PRIu32 "\n", COUNT, node, value->uint());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << COUNT << " " << node << " " << *value << std::endl;
  }
private:
  const char* const node;
  biguint_t* const value;
};

class sample_t {
public:
  sample_t(const char* snap, uint64_t _cycle);
  sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle);
  virtual ~sample_t();

  size_t read_chain(CHAIN_TYPE type, const char* snap, size_t start = 0);
  void add_cmd(sample_inst_t *cmd) { cmds.push_back(cmd); }

  void add_force(force_t *f);
  void dump_forces();

  void dump(FILE *file) const {
    fprintf(file, "%u cycle: %" PRIu64 "\n", CYCLE, cycle);
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

  static void init_chains(std::string filename);
  static size_t get_chain_loop(CHAIN_TYPE t) {
    return chain_loop[t];
  }
  static size_t get_chain_len(CHAIN_TYPE t) {
    return chain_len[t];
  }
  static size_t get_chunks(size_t id) {
    return tr_chunks[id];
  }
  static idmap_it_t in_tr_begin() {
    return in_tr_map.begin();
  }
  static idmap_it_t in_tr_end() {
    return in_tr_map.end();
  }
  static idmap_it_t out_tr_begin() {
    return out_tr_map.begin();
  }
  static idmap_it_t out_tr_end() {
    return out_tr_map.end();
  }
private:
  const uint64_t cycle;
  std::vector<sample_inst_t*> cmds;
  std::vector<std::vector<force_t*>> force_bins;
  size_t force_bin_idx;
  const char* force_prev_node;
  static size_t chain_loop[CHAIN_NUM];
  static size_t chain_len[CHAIN_NUM];
  static std::array<std::vector<std::string>, CHAIN_NUM> signals; 
  static std::array<std::vector<size_t>,      CHAIN_NUM> widths; 
  static std::array<std::vector<ssize_t>,     CHAIN_NUM> depths;
  static idmap_t in_tr_map;
  static idmap_t out_tr_map;
  static std::map<size_t, size_t> tr_chunks;
};

#endif // __SAMPLE_H
