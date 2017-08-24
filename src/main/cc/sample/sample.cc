#include "sample.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <sstream>

#ifdef ENABLE_SNAPSHOT
std::array<std::vector<std::string>, CHAIN_NUM> sample_t::signals = {};
std::array<std::vector<size_t>,      CHAIN_NUM> sample_t::widths  = {};
std::array<std::vector<int>,         CHAIN_NUM> sample_t::depths = {};
size_t sample_t::chain_len[CHAIN_NUM] = {0};
size_t sample_t::chain_loop[CHAIN_NUM] = {0};

void sample_t::init_chains(std::string filename) {
  std::fill(signals.begin(), signals.end(), std::vector<std::string>());
  std::fill(widths.begin(),  widths.end(),  std::vector<size_t>());
  std::fill(depths.begin(), depths.end(), std::vector<int>());
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
    int depth;
    iss >> width >> depth;
    if (signal == "null") signal = "";
    signals[type].push_back(signal);
    widths[type].push_back(width);
    depths[type].push_back(depth);
    chain_len[type] += width;
    switch ((CHAIN_TYPE) type) {
      case SRAM_CHAIN:
      case REGFILE_CHAIN:
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

void sample_t::dump_chains(std::ostream& os) {
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    auto chain_signals = signals[t];
    auto chain_widths = widths[t];
    for (size_t id = 0 ; id < chain_signals.size() ; id++) {
      auto signal = chain_signals[id];
      auto width = chain_widths[id];
      os << SIGNALS << " " << t << " " <<
        (signal.empty() ? "null" : signal) << " " << width << std::endl;
    }
  }
  for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
    os << SIGNALS << " " << IN_TR << " " << IN_TR_NAMES[id] << std::endl;
  }
  for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
    os << SIGNALS << " " << OUT_TR << " " << OUT_TR_NAMES[id] << std::endl;
  }
  for (size_t id = 0, bits_id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
    os << SIGNALS << " " << IN_TR_VALID << " " <<
      (const char*)IN_TR_READY_VALID_NAMES[id] << "_valid" << std::endl;
    os << SIGNALS << " " << IN_TR_READY << " " <<
      (const char*)IN_TR_READY_VALID_NAMES[id] << "_ready" << std::endl;
    for (size_t k = 0 ; k < (size_t)IN_TR_BITS_FIELD_NUMS[id] ; k++, bits_id++) {
      os << SIGNALS << " " << IN_TR_BITS << " " <<
        (const char*)IN_TR_BITS_FIELD_NAMES[bits_id] << std::endl;
    }
  }
  for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
    os << SIGNALS << " " << OUT_TR_VALID << " " <<
      (const char*)OUT_TR_READY_VALID_NAMES[id] << "_valid" << std::endl;
    os << SIGNALS << " " << OUT_TR_READY << " " <<
      (const char*)OUT_TR_READY_VALID_NAMES[id] << "_ready" << std::endl;
    for (size_t k = 0 ; k < (size_t)OUT_TR_BITS_FIELD_NUMS[id] ; k++, bits_id++) {
      os << SIGNALS << " " << OUT_TR_BITS << " " <<
        (const char*)OUT_TR_BITS_FIELD_NAMES[bits_id] << std::endl;
    }
  }
}

size_t sample_t::read_chain(CHAIN_TYPE type, const char* snap, size_t start) {
  size_t t = static_cast<size_t>(type);
  auto chain_signals = signals[t];
  auto chain_widths = widths[t];
  auto chain_depths = depths[t];
  for (size_t i = 0 ; i < chain_loop[type] ; i++) {
    for (size_t s = 0 ; s < chain_signals.size() ; s++) {
      auto signal = chain_signals[s];
      auto width = chain_widths[s];
      auto depth = chain_depths[s];
      if (!signal.empty()) {
        char substr[1025];
        assert(width <= 1024);
        strncpy(substr, snap+start, width);
        substr[width] = '\0';
#ifndef _WIN32
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_str(*value, substr, 2);
#else
        biguint_t* value = new biguint_t(substr, 2);
#endif
        switch(type) {
          case TRACE_CHAIN:
            add_cmd(new force_t(type, s, value));
            break;
          case REGS_CHAIN:
            add_cmd(new load_t(type, s, value));
            break;
          case SRAM_CHAIN:
          case REGFILE_CHAIN:
            if (static_cast<int>(i) < depth)
              add_cmd(new load_t(type, s, value, i));
            break;
          case CNTR_CHAIN:
            add_cmd(new count_t(type, s, value));
            break;
          default:
            break;
        }
      }
      start += width;
    }
    assert(start % DAISY_WIDTH == 0);
  }
  return start;
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
  for (auto& cmd: cmds) delete cmd;
  cmds.clear();
}
