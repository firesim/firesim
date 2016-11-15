#include "sample.h"
#include <cstring>
#include <fstream>
#include <sstream>

#ifdef ENABLE_SNAPSHOT
std::array<std::vector<std::string>, CHAIN_NUM> sample_t::signals = {};
std::array<std::vector<size_t>,      CHAIN_NUM> sample_t::widths  = {};
std::array<std::vector<ssize_t>,     CHAIN_NUM> sample_t::depths = {};
size_t sample_t::chain_len[CHAIN_NUM] = {0};
size_t sample_t::chain_loop[CHAIN_NUM] = {0};

void dump_f(FILE *file,
           SAMPLE_INST_TYPE type,
           const size_t t,
           const size_t id,
           uint32_t* const value,
           const size_t size,
           const int* const idx) {
  fprintf(file, "%u %zu %zu ", type, t, id);
  fprintf(file, "%x", value[size-1]);
  for (int i = size - 2 ; i >= 0 ; i--) {
    fprintf(file, "%08x", value[i]);
  }
  if (idx) fprintf(file, " %d", *idx);
  fprintf(file, "\n");
}

std::ostream& dump_s(std::ostream &os,
                     SAMPLE_INST_TYPE type,
                     const size_t t,
                     const size_t id,
                     uint32_t* const value,
                     const size_t size,
                     const int* const idx) {
  os << type << " " << t << " " << id << " ";
  os << std::hex << value[size-1];
  for (int i = size - 2 ; i >= 0 ; i--) {
    os << std::setfill('0') << std::setw(HEX_WIDTH) << value[i];
  }
  os << std::setfill(' ') << std::setw(0) << std::dec;
  if (idx) os << *idx;
  os << std::endl;
  return os;
}

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
  for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
    fprintf(file, "%u %u %s\n", SIGNALS, IN_TR, IN_TR_NAMES[id]);
  }
  for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
    fprintf(file, "%u %u %s\n", SIGNALS, OUT_TR, OUT_TR_NAMES[id]);
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
  for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
    os << SIGNALS << " " << IN_TR << " " << IN_TR_NAMES[id] << std::endl;
  }
  for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
    os << SIGNALS << " " << OUT_TR << " " << OUT_TR_NAMES[id] << std::endl;
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
        char substr[1025];
        if (width > 1024) throw std::out_of_range("width should be <= 1024");
        strncpy(substr, snap+start, width);
        substr[width] = '\0';
        biguint_t value(substr, 2);
        uint32_t* data = new uint32_t[value.get_size()];
        // memcpy(data, value.get_data(), value.get_size() * sizeof(uint32_t));
        std::copy(value.get_data(), value.get_data() + value.get_size(), data);
        switch(type) {
          case TRACE_CHAIN:
            // add_force(new force_t(s, data, value.get_size()));
            break;
          case REGS_CHAIN:
            add_cmd(new load_t(type, s, data, value.get_size(), -1));
            break;
          case SRAM_CHAIN:
            if (static_cast<ssize_t>(i) < depth)
              add_cmd(new load_t(type, s, data, value.get_size(), i));
            break;
          case CNTR_CHAIN:
            add_cmd(new count_t(type, s, data, value.get_size()));
            break;
          default:
            break;
        }
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
