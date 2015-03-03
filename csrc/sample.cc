#include <algorithm>
#include <stdexcept>
#include <string.h>
#include <assert.h>
#include "sample.h"

std::vector<std::string> sample_t::signals = std::vector<std::string> ();
std::vector<size_t> sample_t::widths = std::vector<size_t> ();

bool sample_cmp (sample_t *a, sample_t *b) {
  return a->t < b->t;
}

sample_t::sample_t(size_t t_, std::string &snap): t(t_) {
  size_t start = 0;
  for (size_t i = 0 ; i < signals.size() ; i++) {
    std::string signal = signals[i];
    size_t width = widths[i];
    if (signal != "null") {
      biguint_t value(snap.substr(start, width).c_str(), 2);
      add_cmd(new poke_t(signal, value));
    }
    start += width;
  }
}

sample_t::~sample_t() {
  for (size_t i = 0 ; i < cmds.size() ; i++) {
    delete cmds[i];
  }
  cmds.clear();
  mem.clear();
}

// Memory merge
sample_t& sample_t::operator+=(const sample_t& that) {
  for (mem_t::const_iterator it = that.mem.begin() ; it != that.mem.end() ; it++) {
    uint64_t addr = it->first;
    biguint_t data = it->second;
    mem[addr] = data;
  }
  return *this;
}

void sample_t::dump(std::ostream &os, std::vector<sample_t*> &samples) {
  std::sort(samples.begin(), samples.end(), sample_cmp);
  for (size_t i = 0 ; i < samples.size() ; i++) {
    os << "99 Sample #" << i << std::endl;
    os << *samples[i];
  }
}

std::ostream& operator<<(std::ostream &os, const sample_t& sample) {
  for (size_t i = 0 ; i < sample.cmds.size() ; i++) {
    sample_inst_t *cmd = sample.cmds[i];
    os << *cmd;
  }
  for (mem_t::const_iterator it = sample.mem.begin() ; it != sample.mem.end() ; it++) {
    uint64_t addr = it->first;
    biguint_t data = it->second;
    os << WRITE << std::hex << " " << addr << " " << data << std::endl;
  }
  os << FIN << std::endl;
  return os;
}

