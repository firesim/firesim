#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <vector>
#include <ostream>
#include "biguint.h"

enum SAMPLE_INST_TYPE { FIN, LOAD, POKE, STEP, EXPECT };

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

class load_t: public sample_inst_t {
public:
  load_t(std::string& node_, biguint_t value_, int off_ = -1): 
    node(node_.c_str()), value(value_), off(off_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << LOAD << " " << node << " " << value << " " << off << std::endl;
  }
private:
  const char* node;
  const biguint_t value;
  const int off;
};

class poke_t: public sample_inst_t {
public:
  poke_t(std::string& node_, biguint_t value_): 
    node(node_.c_str()), value(value_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << POKE << " " << node << " " << value << std::endl;
  } 
private:
  const char* node;
  const biguint_t value;
};

class expect_t: public sample_inst_t {
public:
  expect_t(std::string& node_, biguint_t value_): 
    node(node_.c_str()), value(value_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << EXPECT << " " << node << " " << value << std::endl;
  }
private:
  const char* node;
  const biguint_t value;
};

class sample_t {
public:
  sample_t(std::string& snap) {
    size_t start = 0;
    for (size_t i = 0 ; i < signals.size() ; i++) {
      std::string signal = signals[i];
      size_t width = widths[i];
      int off = offsets[i];
      if (signal != "null") {
        biguint_t value(snap.substr(start, width).c_str(), 2);
        add_cmd(new load_t(signal, value, off));
      }
      start += width;
    }
  }

  ~sample_t() {
    for (size_t i = 0 ; i < cmds.size() ; i++) {
      delete cmds[i];
    }
    cmds.clear();
  }

  void add_cmd(sample_inst_t *cmd) {
    cmds.push_back(cmd);
  }

  static void add_to_chains(std::string signal, size_t width, int off = -1) {
    signals.push_back(signal);
    widths.push_back(width);
    offsets.push_back(off);
  }

  std::ostream& dump(std::ostream &os) const {
    for (size_t i = 0 ; i < cmds.size() ; i++) {
      os << *cmds[i];
    }
    return os;
  }

  friend std::ostream& operator<<(std::ostream& os, const sample_t& s) {
    return s.dump(os);
  }
private:
  std::vector<sample_inst_t*> cmds;
  static std::vector<std::string> signals;
  static std::vector<size_t> widths;
  static std::vector<int> offsets;
};

#endif // __SAMPLE_H
