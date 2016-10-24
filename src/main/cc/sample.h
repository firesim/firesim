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
#ifdef ENABLE_SNAPSHOT
enum { IN_TR = CHAIN_NUM, OUT_TR };
typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;
#endif

struct sample_inst_t {
  virtual ~sample_inst_t() {} 
  virtual void dump(FILE *file) const = 0;  
  virtual std::ostream& dump(std::ostream &os) const = 0;  
  friend std::ostream& operator<<(std::ostream &os, const sample_inst_t& cmd) {
    return cmd.dump(os);
  }
};

struct step_t: sample_inst_t {
  step_t(size_t n_): n(n_) { }
  void dump(FILE *file) const {
    fprintf(file, "%u %zu\n", STEP, n);
  }
  std::ostream& dump(std::ostream &os) const {
    return os << STEP << " " << n << std::endl;
  }
  const size_t n;
};

#ifdef ENABLE_SNAPSHOT
#define _NODE_ node
#else
#define _NODE_ node.c_str()
#endif

struct load_t: sample_inst_t {
  load_t(std::string& node_, biguint_t* value_, int idx_ = -1): 
    node(node_.c_str()), value(value_), idx(idx_) { }
  ~load_t() { delete value; }
  void dump(FILE *file) const {
    fprintf(file, "%u %s %s %d\n", LOAD, _NODE_, value->str().c_str(), idx);
  }
  std::ostream& dump(std::ostream &os) const {
    return os << LOAD << " " << node << " " << *value << " " << idx << std::endl;
  }
#ifdef ENABLE_SNAPSHOT
  const char* const node;
#else
  const std::string node;
#endif
  biguint_t* const value;
  const int idx;
};

struct force_t: sample_inst_t {
  force_t(std::string& node_, biguint_t* value_):
    node(node_.c_str()), value(value_) { }
  ~force_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", FORCE, _NODE_, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << FORCE << " " << node << " " << *value << std::endl;
  }
#ifdef ENABLE_SNAPSHOT
  const char* const node;
#else
  const std::string node;
#endif
  biguint_t* const value;
};

struct poke_t: sample_inst_t {
  poke_t(const std::string& node_, biguint_t* value_):
    node(node_.c_str()), value(value_) { }
  ~poke_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", POKE, _NODE_, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << POKE << " " << node << " " << *value << std::endl;
  }
#ifdef ENABLE_SNAPSHOT
  const char* const node;
#else
  const std::string node;
#endif
  biguint_t* const value;
};

struct expect_t: sample_inst_t {
  expect_t(const std::string& node_, biguint_t* value_):
    node(node_.c_str()), value(value_) { }
  ~expect_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", EXPECT, _NODE_, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << EXPECT << " " << node << " " << *value << std::endl;
  }
#ifdef ENABLE_SNAPSHOT
  const char* const node;
#else
  const std::string node;
#endif
  biguint_t* const value;
};

struct count_t: sample_inst_t {
  count_t(std::string& node_, biguint_t* value_): 
    node(node_.c_str()), value(value_) { }
  ~count_t() { delete value; }
  virtual void dump(FILE *file) const {
    fprintf(file, "%u %s %s\n", COUNT, _NODE_, value->str().c_str());
  }
  std::ostream& dump(std::ostream &os) const {
    return os << COUNT << " " << node << " " << *value << std::endl;
  }
#ifdef ENABLE_SNAPSHOT
  const char* const node;
#else
  const std::string node;
#endif
  biguint_t* const value;
};

class sample_t {
public:
  sample_t(uint64_t _cycle): cycle(_cycle) { }
#ifdef ENABLE_SNAPSHOT
  sample_t(const char* snap, uint64_t _cycle);
  sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle);
#endif
  virtual ~sample_t();

  void add_cmd(sample_inst_t *cmd) { cmds.push_back(cmd); }

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

  inline const uint64_t get_cycle() const { return cycle; }
  inline const std::vector<sample_inst_t*>& get_cmds() const { return cmds; }

#ifdef ENABLE_SNAPSHOT
  void dump_forces();
  void add_force(force_t *f);
  size_t read_chain(CHAIN_TYPE type, const char* snap, size_t start = 0);

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
#endif
private:
  const uint64_t cycle;
  std::vector<sample_inst_t*> cmds;
#ifdef ENABLE_SNAPSHOT
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
#endif
};

#endif // __SAMPLE_H
