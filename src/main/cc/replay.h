#ifndef __REPLAY_H
#define __REPLAY_H

#include <vector>
#include <map>
#include <fstream>
#include <sstream>
#include <iostream>
#include "sample.h"

template<class T> struct replay_data_t {
  std::vector<T> signals;
  std::map<std::string, size_t> signal_map;
};

template <class T> class replay_t {
public:
  replay_t(): cycles(0L), log(false), pass(true), is_exit(false) { }
 
  void init(int argc, char** argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
      if (arg.find("+sample=") == 0) {
        load_samples(arg.c_str() + 8);
      }
      if (arg.find("+verbose") == 0) {
        log = true;
      }
    }
    take_steps(5); // reset 5 cycles
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

  virtual void replay() {
    try {
      for (size_t k = 0 ; k < samples.size() ; k++) {
        sample_t *sample = samples[k];
        for (size_t i = 0 ; i < sample->get_cmds().size() ; i++) {
          sample_inst_t* cmd = sample->get_cmds()[i];
          if (step_t* p = dynamic_cast<step_t*>(cmd)) {
            step(p->n);
          }
          if (load_t* p = dynamic_cast<load_t*>(cmd)) {
            biguint_t& data = *(p->value);
            std::string signal = p->idx < 0 ? p->node :
              p->node + "[" + std::to_string(p->idx) + "]";
            load(signal, data);
          }
          if (poke_t* p = dynamic_cast<poke_t*>(cmd)) {
            biguint_t data(p->value, p->size);
            poke(p->node, data);
          }
          if (expect_t* p = dynamic_cast<expect_t*>(cmd)) {
            biguint_t expected(p->value, p->size);
            pass &= expect(p->node, expected);
          }
        }
      }
    } catch(std::exception& e) {
      pass = false;
      std::cerr << e.what() << std::endl;
    }
    is_exit = true;
  }

protected:
  replay_data_t<T> replay_data;

  bool done() { return is_exit; }
  int exitcode() { return pass ? EXIT_SUCCESS : EXIT_FAILURE; }

private:
  uint64_t cycles;
  bool log;
  bool pass;
  bool is_exit;
  std::vector<sample_t*> samples;
  void load_samples(const char* filename) {
    std::ifstream file(filename);
    if (!file) {
      fprintf(stderr, "Cannot open %s\n", filename);
      exit(EXIT_FAILURE);
    }
    std::string line;
    sample_t* sample = NULL;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      size_t type, n, idx;
      uint64_t cycles;
      std::string signal, dummy;
      biguint_t value, *pvalue = NULL;
      iss >> type;
      switch((SAMPLE_INST_TYPE) type) {
        case CYCLE:
          iss >> dummy >> cycles;
          sample = new sample_t(cycles);
          samples.push_back(sample);
          break;
        case LOAD:
          pvalue = new biguint_t;
          iss >> signal >> *pvalue >> idx;
          sample->add_cmd(new load_t(signal, pvalue, idx));
          break;
        case FORCE:
          pvalue = new biguint_t;
          iss >> signal >> *pvalue;
          sample->add_cmd(new force_t(signal, pvalue));
          break;
        case POKE:
          iss >> signal >> value;
          sample->add_cmd(
            new poke_t(signal, value.get_data(), value.get_size()));
          break;
        case STEP:
          iss >> n;
          sample->add_cmd(new step_t(n));
          break;
        case EXPECT:
          iss >> signal >> value;
          sample->add_cmd(
            new expect_t(signal, value.get_data(), value.get_size()));
          break;
        default:
          break;
      }
    }
    file.close();
  }

  virtual void take_steps(size_t) = 0;
  virtual void put_value(T& sig, biguint_t& data, bool force = false) = 0;
  virtual biguint_t get_value(T& sig) = 0;

  inline void step(size_t n) {
    cycles += n;
    if (log) std::cerr << " * STEP " << n << " -> " << cycles << " *" << std::endl;
    take_steps(n);
  }

  inline void check_signal(const std::string& signal) {
    if (replay_data.signal_map.find(signal) == replay_data.signal_map.end())
      throw std::runtime_error(std::string("Signal map doesn't contain ") + signal);
  }

  inline void force(const std::string& node, biguint_t& data) {
    check_signal(node);
    if (log) std::cerr << " * FORCE " << node << " <- 0x" << data << " *" << std::endl;
    size_t id = replay_data.signal_map[node];
    put_value(replay_data.signals[id], data, true);
  }

  inline void load(const std::string& node, biguint_t& data) {
    check_signal(node);
    if (log) std::cerr << " * LOAD " << node << " <- 0x" << data << " *" << std::endl;
    size_t id = replay_data.signal_map[node];
    put_value(replay_data.signals[id], data, false);
  }

  inline void poke(const std::string& node, biguint_t& data) {
    check_signal(node);
    if (log) std::cerr << " * POKE " << node << " <- 0x" << data << " *" << std::endl;
    size_t id = replay_data.signal_map[node];
    put_value(replay_data.signals[id], data, false);
  }

  inline bool expect(const std::string& node, biguint_t& expected) {
    check_signal(node);
    size_t id = replay_data.signal_map[node];
    biguint_t value = get_value(replay_data.signals[id]);
    bool pass = value == expected || cycles <= 1;
    if (log && cycles > 1) {
      std::cerr << " * EXPECT " << node << " -> 0x" << value << " ?= 0x" << expected;
      std::cerr << (pass ? " : PASS" : " : FAIL") << " *" << std::endl;
    }
    return pass;
  }
};

#endif //__REPLAY_H
