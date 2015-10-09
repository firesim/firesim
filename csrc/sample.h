#ifndef __SAMPLE_H
#define __SAMPLE_H

#include <string>
#include <vector>
#include <ostream>
#include "biguint.h"

enum SAMPLE_INST_TYPE { FIN, LOAD, FORCE, POKE, STEP, EXPECT };

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

class force_t: public sample_inst_t {
public:
  force_t(std::string& node_, biguint_t value_):
    node(node_.c_str()), value(value_) { }
  std::ostream& dump(std::ostream &os) const {
    return os << FORCE << " " << node << " " << value << std::endl;
  }
private:
  const char* node;
  const biguint_t value;
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
    for (size_t i = 0 ; i < sram_signals.size() ; i++) {
      std::string signal = sram_signals[i];
      size_t width = sram_widths[i];
      int off = sram_offsets[i];
      if (signal != "null") {
        biguint_t value(snap.substr(start, width).c_str(), 2);
        add_cmd(new load_t(signal, value, off));
      }
      start += width;
    }

#ifdef WARM_CYCLES
    static const size_t trace_len = trace_signals.size() / WARM_CYCLES;
    for (size_t i = 0 ; i < trace_signals.size() ; i++) {
      std::string signal = trace_signals[i];
      size_t width = trace_widths[i];
      if (signal != "null") {
        biguint_t value(snap.substr(start, width).c_str(), 2);
        add_cmd(new force_t(signal, value));
      }
      bool do_step = (i + 1) % trace_len == 0;
      if (do_step) add_cmd(new step_t(1));
      start += width;
    }
#endif

    for (size_t i = 0 ; i < reg_signals.size() ; i++) {
      std::string signal = reg_signals[i];
      size_t width = reg_widths[i];
      int off = reg_offsets[i];
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

  static void add_to_sram_chains(std::string signal, size_t width, int off = -1) {
    sram_signals.push_back(signal);
    sram_widths.push_back(width);
    sram_offsets.push_back(off);
  }

  static void add_to_trace_chains(std::string signal, size_t width) {
    trace_signals.push_back(signal);
    trace_widths.push_back(width);
  }

  static void add_to_reg_chains(std::string signal, size_t width, int off = -1) {
    reg_signals.push_back(signal);
    reg_widths.push_back(width);
    reg_offsets.push_back(off);
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
  static std::vector<std::string> reg_signals;
  static std::vector<size_t> reg_widths;
  static std::vector<int> reg_offsets;
  static std::vector<std::string> trace_signals;
  static std::vector<size_t> trace_widths;
  static std::vector<std::string> sram_signals;
  static std::vector<size_t> sram_widths;
  static std::vector<int> sram_offsets;
};

#endif // __SAMPLE_H
