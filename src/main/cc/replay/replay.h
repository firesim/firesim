#ifndef __REPLAY_H
#define __REPLAY_H

#include <vector>
#include <map>
#include <fstream>
#include <sstream>
#include <iostream>
#include <cassert>
#include "sample/sample.h"

enum PUT_VALUE_TYPE { PUT_DEPOSIT, PUT_FORCE };
static const char* PUT_VALUE_TYPE_STRING[2] = { "LOAD", "FORCE" };

template <class T> class replay_t {
public:
  replay_t(): cycles(0L), log(false), pass(true), is_exit(false) { }
 
  void init(int argc, char** argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
      if (arg.find("+sample=") == 0) {
        load_samples(arg.c_str() + 8);
      }
      if (arg.find("+match=") == 0) {
        load_match_points(arg.c_str() + 7);
      }
      if (arg.find("+verbose") == 0) {
        log = true;
      }
    }
  }
 
  void reset(size_t n) {
    biguint_t one = 1;
    size_t id = replay_data.signal_map["reset"];
    put_value(replay_data.signals[id], &one, PUT_DEPOSIT);
    take_steps(n);
  }

  virtual void replay() {
    for (size_t k = 0 ; k < samples.size() ; k++) {
      sample_t *sample = samples[k];
      reset(5);
      std::cerr << " * REPLAY AT CYCLE " << sample->get_cycle() << " * " << std::endl;
      for (size_t i = 0 ; i < sample->get_cmds().size() ; i++) {
        sample_inst_t* cmd = sample->get_cmds()[i];
        if (step_t* p = dynamic_cast<step_t*>(cmd)) {
          step(p->n);
        }
        if (load_t* p = dynamic_cast<load_t*>(cmd)) {
          auto signal = signals[p->type][p->id];
          auto width = widths[p->type][p->id];
          load(signal, width, p->value, PUT_DEPOSIT, p->idx);
        }
        if (force_t* p = dynamic_cast<force_t*>(cmd)) {
          auto signal = signals[p->type][p->id];
          auto width = widths[p->type][p->id];
          load(signal, width, p->value, PUT_FORCE, -1);
        }
        if (poke_t* p = dynamic_cast<poke_t*>(cmd)) {
          poke(signals[p->type][p->id], p->value);
        }
        if (expect_t* p = dynamic_cast<expect_t*>(cmd)) {
          pass &= expect(signals[p->type][p->id], p->value);
        }
      }
    }
    is_exit = true;
  }

  virtual int finish() {
    fprintf(stderr, "[%s] Runs %" PRIu64 " cycles\n",
      pass ? "PASS" : "FAIL", cycles);
    for (size_t i = 0 ; i < samples.size() ; i++) {
      delete samples[i];
    }
    samples.clear();
    return exitcode();
  }

protected:
  struct {
    std::vector<T> signals;
    std::map<std::string, size_t> signal_map;
  } replay_data;

  inline bool gate_level() { return !match_map.empty(); }
  inline bool done() { return is_exit; }
  inline int exitcode() { return pass ? EXIT_SUCCESS : EXIT_FAILURE; }

private:
  uint64_t cycles;
  bool log;
  bool pass;
  bool is_exit;
  std::vector<sample_t*> samples;
  std::vector<std::vector<std::string>> signals;
  std::vector<std::vector<size_t>> widths;
  std::map<std::string, std::string> match_map;

  void load_samples(const char* filename) {
    std::ifstream file(filename);
    if (!file) {
      fprintf(stderr, "Cannot open %s\n", filename);
      exit(EXIT_FAILURE);
    }
    std::string line;
    size_t steps = 0;
    sample_t* sample = NULL;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      size_t type, t, width, id, n;
      ssize_t idx;
      uint64_t cycles;
      std::string signal, dummy;
      biguint_t *value = NULL;
      iss >> type;
      switch(static_cast<SAMPLE_INST_TYPE>(type)) {
        case SIGNALS:
          iss >> t >> signal >> width;
          while(signals.size() <= t) signals.push_back(std::vector<std::string>());
          while(widths.size() <= t) widths.push_back(std::vector<size_t>());
          signals[t].push_back(signal);
          widths[t].push_back(width);
          break;
        case CYCLE:
          iss >> dummy >> cycles;
          sample = new sample_t(cycles);
          samples.push_back(sample);
          steps = 0;
          break;
        case LOAD:
          value = new biguint_t;
          iss >> t >> id >> *value >> idx;
          sample->add_cmd(new load_t(t, id, value, idx));
          break;
        case FORCE:
          value = new biguint_t;
          iss >> t >> id >> *value;
          sample->add_cmd(new force_t(t, id, value));
          break;
        case POKE:
          value = new biguint_t;
          iss >> t >> id >> *value;
          sample->add_cmd(new poke_t(t, id, value));
          break;
        case STEP:
          iss >> n;
          sample->add_cmd(new step_t(n));
          steps += n;
          break;
        case EXPECT:
          value = new biguint_t;
          iss >> t >> id >> *value;
          if (steps > 1) sample->add_cmd(new expect_t(t, id, value));
          break;
        default:
          break;
      }
    }
    file.close();
  }

  void load_match_points(const char* filename) {
    std::ifstream file(filename);
    if (!file) {
      fprintf(stderr, "Cannot open %s\n", filename);
      exit(EXIT_FAILURE);
    }
    std::string line;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      std::string ref, impl;
      iss >> ref >> impl;
      match_map[ref] = impl;
    }
  }

  virtual void take_steps(size_t) = 0;
  virtual void put_value(T& sig, biguint_t* data, PUT_VALUE_TYPE type) = 0;
  virtual biguint_t get_value(T& sig) = 0;

  void step(size_t n) {
    cycles += n;
    if (log) std::cerr << " * STEP " << n << " -> " << cycles << " *" << std::endl;
    take_steps(n);
  }

  T& get_signal(const std::string& node) {
    auto it = replay_data.signal_map.find(node);
    if (it == replay_data.signal_map.end()) {
      std::cerr << "Cannot find " << node << " in the design" << std::endl;
      assert(false);
    }
    return replay_data.signals[it->second];
  }

  void load_bit(const std::string& ref, biguint_t* bit, PUT_VALUE_TYPE tpe) {
    auto it = match_map.find(ref);
    if (it != match_map.end()) {
      put_value(get_signal(it->second), bit, tpe);
    }
  }

  void load(const std::string& node, size_t width, biguint_t* data, PUT_VALUE_TYPE tpe, int idx) {
    std::string name = idx < 0 ? node : node + "[" + std::to_string(idx) + "]";
    if (log) {
      std::cerr << " * " << PUT_VALUE_TYPE_STRING[tpe] << " ";
      std::cerr << name << " <- 0x" << *data << " *" << std::endl;
    }
    if (!gate_level()) {
      put_value(get_signal(name), data, tpe);
    } else if (width == 1 && idx < 0) {
      load_bit(name, data, tpe);
    } else {
      for (size_t i = 0 ; i < width ; i++) {
        biguint_t bit = (*data >> i) & 0x1;
        load_bit(name + "[" + std::to_string(i) + "]", &bit, tpe);
      }
    }
  }

  void poke(const std::string& node, biguint_t* data) {
    if (log) std::cerr << " * POKE " << node << " <- 0x" << *data << " *" << std::endl;
    put_value(get_signal(node), data, PUT_DEPOSIT);
  }

  bool expect(const std::string& node, biguint_t* expected) {
    biguint_t value = get_value(get_signal(node));
    bool pass = value == *expected || cycles <= 1;
    if (log) {
      std::cerr << " * EXPECT " << node << " -> 0x" << value << " ?= 0x" << *expected;
      std::cerr << (pass ? " : PASS" : " : FAIL") << " *" << std::endl;
    }
    return pass;
  }
};

#endif //__REPLAY_H
