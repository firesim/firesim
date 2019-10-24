// See LICENSE for license details.

#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <array>
#include <vector>
#include <map>
#include <ostream>
#include <inttypes.h>
#include <gmp.h>

enum SAMPLE_INST_TYPE { SIGNALS, CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT };
#ifdef ENABLE_SNAPSHOT
enum { IN_TR = CHAIN_NUM,
       OUT_TR,
       IN_TR_VALID,
       IN_TR_READY,
       IN_TR_BITS,
       OUT_TR_VALID,
       OUT_TR_READY,
       OUT_TR_BITS };
#endif

struct sample_inst_t {
  virtual ~sample_inst_t() {}
  virtual std::ostream& dump(std::ostream &os) const = 0;
  friend std::ostream& operator<<(std::ostream &os, const sample_inst_t& cmd) {
    return cmd.dump(os);
  }
};

struct step_t: sample_inst_t {
  step_t(size_t n_): n(n_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << STEP << " " << n << std::endl;
  }
  const size_t n;
};

struct load_t: sample_inst_t {
  load_t(const size_t type, const size_t id, mpz_t* value, const int idx = -1):
    type(type), id(id), value(value), idx(idx) { }
  ~load_t() {
    mpz_clear(*value);
    free(value);
  }
  std::ostream& dump(std::ostream &os) const {
    char* value_str = mpz_get_str(NULL, 16, *value);
    os << LOAD << " " << type << " " << id << " " << value_str << " " << idx << std::endl;
    free(value_str);
    return os;
  }

  const size_t type;
  const size_t id;
  mpz_t* const value;
  const int idx;
};

struct force_t: sample_inst_t {
  force_t(const size_t type, const size_t id, mpz_t* value):
    type(type), id(id), value(value) { }
  ~force_t() {
    mpz_clear(*value);
    free(value);
  }
  std::ostream& dump(std::ostream &os) const {
    char* value_str = mpz_get_str(NULL, 16, *value);
    os << FORCE << " " << type << " " << id << " " << value_str << std::endl;
    free(value_str);
    return os;
  }

  const size_t type;
  const size_t id;
  mpz_t* const value;
};

struct poke_t: sample_inst_t {
  poke_t(const size_t type, const size_t id, mpz_t* value):
    type(type), id(id), value(value) { }
  ~poke_t() {
    mpz_clear(*value);
    free(value);
  }
  std::ostream& dump(std::ostream &os) const {
    char* value_str = mpz_get_str(NULL, 16, *value);
    os << POKE << " " << type << " " << id << " " << value_str << std::endl;
    free(value_str);
    return os;
  }

  const size_t type;
  const size_t id;
  mpz_t* const value;
};

struct expect_t: sample_inst_t {
  expect_t(const size_t type, const size_t id, mpz_t* value):
    type(type), id(id), value(value) { }
  ~expect_t() {
    mpz_clear(*value);
    free(value);
  }
  std::ostream& dump(std::ostream &os) const {
    char* value_str = mpz_get_str(NULL, 16, *value);
    os << EXPECT << " " << type << " " << id << " " << value_str << std::endl;
    free(value_str);
    return os;
  }

  const size_t type;
  const size_t id;
  mpz_t* const value;
};

struct count_t: sample_inst_t {
  count_t(const size_t type, const size_t id, mpz_t* value):
    type(type), id(id), value(value) { }
  ~count_t() {
    mpz_clear(*value);
    free(value);
  }
  std::ostream& dump(std::ostream &os) const {
    char* value_str = mpz_get_str(NULL, 16, *value);
    os << COUNT << " " << type << " " << id << " " << value_str << std::endl;
    free(value_str);
    return os;
  }

  const size_t type;
  const size_t id;
  mpz_t* const value;
};

class sample_t {
public:
  sample_t(uint64_t _cycle): cycle(_cycle) { }
#ifdef ENABLE_SNAPSHOT
  sample_t(const char* snap, uint64_t _cycle);
  sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle);

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
  static std::array<std::vector<int>,         CHAIN_NUM> depths;
#endif
};

#endif // __SAMPLE_H
