#include "sample.h"
#include <cassert>

std::array<std::vector<std::string>, CHAIN_NUM> sample_t::signals = {};
std::array<std::vector<size_t>,      CHAIN_NUM> sample_t::widths  = {};
std::array<std::vector<ssize_t>,     CHAIN_NUM> sample_t::indices = {};

void sample_t::init_chains() {
  std::fill(signals.begin(), signals.end(), std::vector<std::string>());
  std::fill(widths.begin(),  widths.end(),  std::vector<size_t>());
  std::fill(indices.begin(), indices.end(), std::vector<ssize_t>());
}

void sample_t::add_to_chains(CHAIN_TYPE type, std::string& signal, size_t width, int index) {
  signals[type].push_back(signal);
  widths[type].push_back(width);
  indices[type].push_back(index);
}

sample_t::sample_t(std::string& snap) {
  size_t start = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    std::vector<std::string> chain_signals = signals[t];
    std::vector<size_t> chain_widths = widths[t];
    std::vector<ssize_t> chain_indices = indices[t];
    for (size_t i = 0 ; i < CHAIN_LOOP[type] ; i++) {
      for (size_t s = 0 ; s < chain_signals.size() ; s++) {
        std::string signal = chain_signals[s];
        size_t width = chain_widths[s];
        ssize_t index = chain_indices[s];
        if (!signal.empty()) {
          biguint_t value(snap.substr(start, width).c_str(), 2);
          if (type == SRAM_CHAIN && ((ssize_t) i) < index) {
            add_cmd(new load_t(signal, value, i));
          } else if (type == TRACE_CHAIN) {
            add_cmd(new force_t(signal, value)); 
          } else if (type == REG_CHAIN) {
            add_cmd(new load_t(signal, value, index));
          }
        }
        start += width;
      }
      if (type == TRACE_CHAIN) add_cmd(new step_t(1));
      assert(start % DAISY_WIDTH == 0);
    }
  }
}

sample_t::~sample_t() {
  for (size_t i = 0 ; i < cmds.size() ; i++) {
    delete cmds[i];
  }
  cmds.clear();
}
