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
      switch ((CHAIN_TYPE) type) {
        case SRAM_CHAIN:
          if (!signal.empty() && depth > 0) {
            chain_loop[type] = std::max(chain_loop[type], (size_t) depth);
          }
          break;
        default:
          chain_loop[type] = 1;
          break;
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

void sample_t::dump_chains(FILE* file) {
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    std::vector<std::string> &chain_signals = signals[t];
    for (size_t id = 0 ; id < chain_signals.size() ; id++) {
      std::string &signal = chain_signals[id];
      fprintf(file, "%u %zu %s\n", SIGNALS, t, signal.empty() ? "null" : signal.c_str());
    }
  }
  for (idmap_it_t it = in_tr_map.begin() ; it != in_tr_map.end() ; it++) {
    fprintf(file, "%u %u %s\n", SIGNALS, IN_TR, (it->first).c_str());
  }
  for (idmap_it_t it = out_tr_map.begin() ; it != out_tr_map.end() ; it++) {
    fprintf(file, "%u %u %s\n", SIGNALS, OUT_TR, (it -> first).c_str());
  }
}

void sample_t::dump_chains(std::ostream& os) {
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    std::vector<std::string> &chain_signals = signals[t];
    for (size_t id = 0 ; id < chain_signals.size() ; id++) {
      std::string &signal = chain_signals[id];
      os << SIGNALS << " " << t << " " << (signal.empty() ? "null" : signal) << std::endl;
    }
  }
  for (idmap_it_t it = in_tr_map.begin() ; it != in_tr_map.end() ; it++) {
    os << SIGNALS << " " << IN_TR << " " << it->first << std::endl;
  }
  for (idmap_it_t it = out_tr_map.begin() ; it != out_tr_map.end() ; it++) {
    os << SIGNALS << " " << OUT_TR << " " << it->first << std::endl;
  }
}

size_t sample_t::read_chain(CHAIN_TYPE type, const char* snap, size_t start) {
  size_t t = static_cast<size_t>(type);
  std::vector<std::string> &chain_signals = signals[t];
  std::vector<size_t> &chain_widths = widths[t];
  std::vector<ssize_t> &chain_depths = depths[t];
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
        switch(type) {
          case TRACE_CHAIN:
            // add_force(new force_t(s, value));
            break;
          case REGS_CHAIN:
            add_cmd(new load_t(type, s, value, -1));
            break;
          case SRAM_CHAIN:
            if (static_cast<ssize_t>(i) < depth)
              add_cmd(new load_t(type, s, value, i));
            break;
          case CNTR_CHAIN:
            add_cmd(new count_t(type, s, value));
            break;
          default:
            break;
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
  force_bin_idx = f->id == force_prev_id ? force_bin_idx + 1 : 0;
  if (force_bins.size() < force_bin_idx + 1) {
    force_bins.push_back(std::vector<force_t*>());
  }
  force_bins[force_bin_idx].push_back(f);
  force_prev_id = f->id;
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
  force_prev_id = -1;
}

sample_t::sample_t(const char* snap, uint64_t _cycle):
    cycle(_cycle), force_prev_id(-1) {
  size_t start = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    start = read_chain(type, snap, start);
  }
}

sample_t::sample_t(CHAIN_TYPE type, const char* snap, uint64_t _cycle):
    cycle(_cycle), force_prev_id(-1) {
  read_chain(type, snap);
}
#endif

sample_t::~sample_t() {
  for (size_t i = 0 ; i < cmds.size() ; i++) {
    delete cmds[i];
  }
  cmds.clear();
}
