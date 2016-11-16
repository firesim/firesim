#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <array>
#include <vector>
#include <map>
#include <ostream>
#include <inttypes.h>
#include "biguint.h"

enum SAMPLE_INST_TYPE { SIGNALS, CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT };
#ifdef ENABLE_SNAPSHOT
enum { IN_TR = CHAIN_NUM, OUT_TR };

void dump_f(
  FILE *file,
  const SAMPLE_INST_TYPE type,
  const size_t t,
  const size_t id,
  uint32_t* const value,
  const size_t size,
  const int* const idx = NULL);

std::ostream& dump_s(
  std::ostream &os,
  const SAMPLE_INST_TYPE type,
  const size_t t,
  const size_t id,
  uint32_t* const value,
  const size_t size,
  const int* const idx = NULL);
#endif

struct sample_inst_t {
  virtual ~sample_inst_t() {}
#ifdef ENABLE_SNAPSHOT
  virtual void dump(FILE *file) const = 0;
  virtual std::ostream& dump(std::ostream &os) const = 0;
  friend std::ostream& operator<<(std::ostream &os, const sample_inst_t& cmd) {
    return cmd.dump(os);
  }
#endif
};

struct step_t: sample_inst_t {
  step_t(size_t n_): n(n_) { }
#ifdef ENABLE_SNAPSHOT
  void dump(FILE *file) const {
    fprintf(file, "%u %zu\n", STEP, n);
  }
  std::ostream& dump(std::ostream &os) const {
    return os << " " << STEP << " " << n << std::endl;
  }
#endif
  const size_t n;
};

struct load_t: sample_inst_t {
#ifdef ENABLE_SNAPSHOT
  load_t(const size_t type_, const size_t id_, uint32_t* value_, const size_t size_, const int idx_ = -1):
    type(type_), id(id_), value(value_), size(size_), idx(idx_) { }
  void dump(FILE *file) const {
    dump_f(file, LOAD, type, id, value, size, &idx);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, LOAD, type, id, value, size, &idx);
  }
#else
  load_t(const size_t type_, const size_t id_, biguint_t* value_, const int idx_ = -1):
    type(type_), id(id_), value(value_), idx(idx_) { }
#endif
  ~load_t() { delete value; }

  const size_t type;
  const size_t id;
#ifdef ENABLE_SNAPSHOT
  uint32_t* const value;
  const size_t size;
#else
  biguint_t* const value;
#endif
  const int idx;
};

struct force_t: sample_inst_t {
#ifdef ENABLE_SNAPSHOT
  force_t(const size_t type_, const size_t id_, uint32_t* value_, const size_t size_):
    type(type_), id(id_), value(value_), size(size_) { }
  virtual void dump(FILE *file) const {
    dump_f(file, FORCE, type, id, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, FORCE, type, id, value, size);
  }
#else
  force_t(const size_t type_, const size_t id_, biguint_t* value_):
    type(type_), id(id_), value(value_) { }
#endif
  ~force_t() { delete value; }

  const size_t type;
  const size_t id;
#ifdef ENABLE_SNAPSHOT
  uint32_t* const value;
  const size_t size;
#else
  biguint_t* const value;
#endif
};

struct poke_t: sample_inst_t {
#ifdef ENABLE_SNAPSHOT
  poke_t(const size_t type_, const size_t id, uint32_t* value_, const size_t size_):
    type(type_), id(id), value(value_), size(size_) { }
  virtual void dump(FILE *file) const {
    dump_f(file, POKE, type, id, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, POKE, type, id, value, size);
  }
#else
  poke_t(const size_t type_, const size_t id, biguint_t* value_):
    type(type_), id(id), value(value_) { }
#endif
  ~poke_t() { delete value; }

  const size_t type;
  const size_t id;
#ifdef ENABLE_SNAPSHOT
  uint32_t* const value;
  const size_t size;
#else
  biguint_t* const value;
#endif
};

struct expect_t: sample_inst_t {
#ifdef ENABLE_SNAPSHOT
  expect_t(const size_t type_, const size_t id_, uint32_t* value_, const size_t size_):
    type(type_), id(id_), value(value_), size(size_) { }
  virtual void dump(FILE *file) const {
    dump_f(file, EXPECT, type, id, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, EXPECT, type, id, value, size);
  }
#else
  expect_t(const size_t type_, const size_t id_, biguint_t* value_):
    type(type_), id(id_), value(value_) { }
#endif
  ~expect_t() { delete value; }

  const size_t type;
  const size_t id;
#ifdef ENABLE_SNAPSHOT
  uint32_t* const value;
  const size_t size;
#else
  biguint_t* const value;
#endif
};

struct count_t: sample_inst_t {
#ifdef ENABLE_SNAPSHOT
  count_t(const size_t type_, const size_t id_, uint32_t* value_, const size_t size_):
    type(type_), id(id_), value(value_), size(size_) { }
  virtual void dump(FILE *file) const {
    dump_f(file, COUNT, type, id, value, size);
  }
  std::ostream& dump(std::ostream &os) const {
    return dump_s(os, COUNT, type, id, value, size);
  }
#else
  count_t(const size_t type_, const size_t id_, biguint_t* value_):
    type(type_), id(id_), value(value_) { }
#endif
  ~count_t() { delete value; }

  const size_t type;
  const size_t id;
#ifdef ENABLE_SNAPSHOT
  uint32_t* const value;
  const size_t size;
#else
  biguint_t* const value;
#endif
};

class sample_t {
public:
  sample_t(uint64_t _cycle): cycle(_cycle) { }
#ifdef ENABLE_SNAPSHOT
  sample_t(const char* snap, uint64_t _cycle);
  sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle);
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
#endif
  virtual ~sample_t();

  void add_cmd(sample_inst_t *cmd) { cmds.push_back(cmd); }

  inline const uint64_t get_cycle() const { return cycle; }
  inline const std::vector<sample_inst_t*>& get_cmds() const { return cmds; }

#ifdef ENABLE_SNAPSHOT
  void dump_forces();
  void add_force(force_t *f);
  size_t read_chain(CHAIN_TYPE type, const char* snap, size_t start = 0);

  static void init_chains(std::string filename);
  static void dump_chains(FILE *file);
  static void dump_chains(std::ostream &os);
  static size_t get_chain_loop(CHAIN_TYPE t) {
    return chain_loop[t];
  }
  static size_t get_chain_len(CHAIN_TYPE t) {
    return chain_len[t];
  }
#endif
private:
  const uint64_t cycle;
  std::vector<sample_inst_t*> cmds;
#ifdef ENABLE_SNAPSHOT
  std::vector<std::vector<force_t*>> force_bins;
  size_t force_bin_idx;
  size_t force_prev_id;
  static size_t chain_loop[CHAIN_NUM];
  static size_t chain_len[CHAIN_NUM];
  static std::array<std::vector<std::string>, CHAIN_NUM> signals; 
  static std::array<std::vector<size_t>,      CHAIN_NUM> widths; 
  static std::array<std::vector<ssize_t>,     CHAIN_NUM> depths;
#endif
};

#endif // __SAMPLE_H
