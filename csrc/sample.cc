#include "sample.h"
#include <cassert>
#include <cstring>

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

size_t sample_t::read_chain(CHAIN_TYPE type, const char* snap, size_t start) {
  size_t t = static_cast<size_t>(type);
  std::vector<std::string> chain_signals = signals[t];
  std::vector<size_t> chain_widths = widths[t];
  std::vector<ssize_t> chain_indices = indices[t];
  for (size_t i = 0 ; i < CHAIN_LOOP[type] ; i++) {
    for (size_t s = 0 ; s < chain_signals.size() ; s++) {
      std::string &signal = chain_signals[s];
      size_t width = chain_widths[s];
      ssize_t index = chain_indices[s];
      if (!signal.empty()) {
        char* substr = new char[width+1];
        strncpy(substr, snap+start, width);
        substr[width] = '\0';
        biguint_t* value = new biguint_t(substr, 2);
        if (type == TRACE_CHAIN) {
          add_force(new force_t(signal, value)); 
        } else if (type == REG_CHAIN) {
          add_cmd(new load_t(signal, value, index));
        } else if (type == SRAM0_CHAIN && ((ssize_t) i) < index) {
          add_cmd(new load_t(signal, value, i));
        } else if (type == SRAM1_CHAIN && ((ssize_t) i) < index) {
          add_cmd(new load_t(signal, value, i));
        } else if (type == CNTR_CHAIN) {
          add_cmd(new count_t(signal, value));
        }
        delete[] substr;
      }
      start += width;
    }
    assert(start % DAISY_WIDTH == 0);
  }
  if (type == TRACE_CHAIN) dump_forces();
  return start;
}

sample_t::sample_t(const char* snap, uint64_t _cycle): 
    cycle(_cycle), force_prev_node(NULL) {
  size_t start = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t); 
    start = read_chain(type, snap, start);
  }
}

sample_t::sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle): 
    cycle(_cycle), force_prev_node(NULL) {
  read_chain(type, snap);
}

sample_t::~sample_t() {
  for (size_t i = 0 ; i < cmds.size() ; i++) {
    delete cmds[i];
  }
  cmds.clear();
}

void sample_t::add_force(force_t* f) {
  force_bin_idx = force_prev_node && 
    strcmp(f->name(), force_prev_node) == 0 ? force_bin_idx + 1 : 0;
  if (force_bins.size() < force_bin_idx + 1) {
    force_bins.push_back(std::vector<force_t*>());
  }
  force_bins[force_bin_idx].push_back(f);
  force_prev_node = f->name();
}

void sample_t::dump_forces() {
  for (ssize_t i = force_bins.size() - 1 ; i >= 0 ; i--) {
    std::vector<force_t*> force_bin = force_bins[i];
    for (size_t k = 0 ; k < force_bin.size() ; k++) {
      cmds.push_back(force_bin[k]);
    }
    cmds.push_back(new step_t(1));
    force_bin.clear();
  }
  force_prev_node = NULL;
}

poke_t::poke_t(const std::string &node_, uint32_t* value_, size_t size_):
    node(node_.c_str()), size(size_) {
  assert(size > 0);
  value = new uint32_t[size];
  memcpy(value, value_, size*sizeof(uint32_t));
}

expect_t::expect_t(const std::string &node_, uint32_t* value_, size_t size_):
    node(node_.c_str()), size(size_) {
  assert(size > 0);
  value = new uint32_t[size];
  memcpy(value, value_, size*sizeof(uint32_t));
}

void dump_f(FILE *file, 
    SAMPLE_INST_TYPE type, const char* node, uint32_t* value, size_t size) {
  fprintf(file, "%u %s ", type, node);
  fprintf(file, "%x", value[size-1]);
  for (int i = size - 2 ; i >= 0 ; i--) {
    fprintf(file, "%08x", value[i]);
  }
  fprintf(file, "\n");
}

std::ostream& dump_s(std::ostream &os, 
    SAMPLE_INST_TYPE type, const char* node, uint32_t* value, size_t size) {
  os << type << " " << node << " ";
  os << std::hex;
  os << value[size-1];
  os << std::setfill('0') << std::setw(HEX_WIDTH);
  for (int i = size - 2 ; i >= 0 ; i--) {
    os << value[i];
  }
  os << std::setfill(' ') << std::setw(0) << std::dec << std::endl;;
  return os;
}
