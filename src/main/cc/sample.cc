#include "sample.h"
#include <cstring>
#include <fstream>
#include <sstream>

#ifdef ENABLE_SNAPSHOT
std::array<std::vector<std::string>, CHAIN_NUM> sample_t::signals = {};
std::array<std::vector<size_t>,      CHAIN_NUM> sample_t::widths  = {};
std::array<std::vector<ssize_t>,     CHAIN_NUM> sample_t::depths = {};
idmap_t sample_t::in_tr_map = idmap_t();
idmap_t sample_t::out_tr_map = idmap_t();
std::map<size_t, size_t> sample_t::tr_chunks = std::map<size_t, size_t>();
size_t sample_t::chain_len[CHAIN_NUM] = {0};
size_t sample_t::chain_loop[CHAIN_NUM] = {0};

void sample_t::init_chains(std::string filename) {
  std::fill(signals.begin(), signals.end(), std::vector<std::string>());
  std::fill(widths.begin(),  widths.end(),  std::vector<size_t>());
  std::fill(depths.begin(), depths.end(), std::vector<ssize_t>());
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  std::string line;
  while (std::getline(file, line)) {
    std::istringstream iss(line);
    size_t type;
    std::string signal;
    iss >> type >> signal;
    if (type < CHAIN_NUM) {
      size_t width;
      ssize_t depth;
      iss >> width >> depth;
      if (signal == "null") signal = "";
      signals[type].push_back(signal);
      widths[type].push_back(width);
      depths[type].push_back(depth);
      chain_len[type] += width;
      if (type == SRAM_CHAIN && !signal.empty()) {
        if (depth <= 0) throw std::runtime_error("depth should be > 0");
        chain_loop[type] = std::max(chain_loop[type], (size_t) depth);
      } else {
        chain_loop[type] = 1;
      }
    } else {
      size_t id, chunk;
      iss >> id >> chunk;
      tr_chunks[id] = chunk;
      if (type == IN_TR) {
        in_tr_map[signal] = id;
      } else if (type == OUT_TR) {
        out_tr_map[signal] = id;
      }
    }
  }
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    chain_len[t] /= DAISY_WIDTH;
  }
  file.close();
}

size_t sample_t::read_chain(CHAIN_TYPE type, const char* snap, size_t start) {
  size_t t = static_cast<size_t>(type);
  std::vector<std::string> chain_signals = signals[t];
  std::vector<size_t> chain_widths = widths[t];
  std::vector<ssize_t> chain_depths = depths[t];
  for (size_t i = 0 ; i < chain_loop[type] ; i++) {
    for (size_t s = 0 ; s < chain_signals.size() ; s++) {
      std::string &signal = chain_signals[s];
      size_t width = chain_widths[s];
      ssize_t depth = chain_depths[s];
      if (!signal.empty()) {
        char* substr = new char[width+1];
        strncpy(substr, snap+start, width);
        substr[width] = '\0';
        biguint_t* value = new biguint_t(substr, 2);
        if (type == TRACE_CHAIN) {
          // add_force(new force_t(signal, value)); 
        } else if (type == REGS_CHAIN) {
          add_cmd(new load_t(signal, value, -1));
        } else if (type == SRAM_CHAIN && ((ssize_t) i) < depth) {
          add_cmd(new load_t(signal, value, i));
        } else if (type == CNTR_CHAIN) {
          add_cmd(new count_t(signal, value));
        }
        delete[] substr;
      }
      start += width;
    }
    if (start % DAISY_WIDTH > 0)
      throw std::runtime_error("start %% DAISY_WIDTH should be 0");
  }
  // if (type == TRACE_CHAIN) dump_forces();
  return start;
}

void sample_t::add_force(force_t* f) {
  force_bin_idx = force_prev_node && 
    strcmp(f->node, force_prev_node) == 0 ? force_bin_idx + 1 : 0;
  if (force_bins.size() < force_bin_idx + 1) {
    force_bins.push_back(std::vector<force_t*>());
  }
  force_bins[force_bin_idx].push_back(f);
  force_prev_node = f->node;
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
#endif

sample_t::~sample_t() {
  for (size_t i = 0 ; i < cmds.size() ; i++) {
    delete cmds[i];
  }
  cmds.clear();
}
