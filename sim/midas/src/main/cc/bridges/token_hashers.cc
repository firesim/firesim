#include "token_hashers.h"
#include "core/simif.h"

#include <fstream>
#include <iostream>
#include <sstream>

uint32_t XORHash32::get() { return lfsr; }

uint32_t XORHash32::next(const uint32_t input) {
  const uint32_t stage0 = lfsr ^ input;
  const uint32_t stage1 = stage0 ^ (stage0 << 13);
  const uint32_t stage2 = stage1 ^ (stage1 >> 17);
  lfsr = stage2 ^ (stage2 << 5);

  return lfsr;
}

char token_hashers_t::KIND;

/**
 * The constructor for token_hashers_t.
 *
 */
token_hashers_t::token_hashers_t(simif_t &sim,
                                 const std::vector<std::string> &args,
                                 const TOKENHASHMASTER_struct &s,
                                 const uint32_t cnt,
                                 const char *const *bridge_names,
                                 const char *const *names,
                                 const uint32_t *const outputs,
                                 const uint32_t *const queue_heads,
                                 const uint32_t *const queue_occupancies,
                                 const uint32_t *const tokencounts0,
                                 const uint32_t *const tokencounts1)
    : bridge_driver_t(sim, &KIND), trigger0(s.triggerDelay0_TokenHashMaster),
      trigger1(s.triggerDelay1_TokenHashMaster),
      period0(s.triggerPeriod0_TokenHashMaster),
      period1(s.triggerPeriod1_TokenHashMaster), cnt(cnt),
      bridge_names(bridge_names, bridge_names + cnt), names(names, names + cnt),
      outputs(outputs, outputs + cnt),
      queue_heads(queue_heads, queue_heads + cnt),
      queue_occupancies(queue_occupancies, queue_occupancies + cnt),
      tokencounts0(tokencounts0, tokencounts0 + cnt),
      tokencounts1(tokencounts1, tokencounts1 + cnt) {}

token_hashers_t::~token_hashers_t() = default;

void token_hashers_t::init() {}
/**
 * Print debug info about the MMIO internals
 */
void token_hashers_t::info(std::ostream &os) {
  os << "trigger0 " << trigger0 << "\n";
  os << "trigger1 " << trigger1 << "\n";
  os << "period0 " << period0 << "\n";
  os << "period1 " << period1 << "\n";

  for (uint32_t i = 0; i < cnt; i++) {
    os << "i: " << i << "\n";
    os << "  bridge: " << bridge_names[i] << "\n";
    os << "  name: " << names[i] << "\n";
    os << "  direction: " << (outputs[i] ? "Output" : "Input") << "\n";
    os << "  queue_head: " << queue_heads[i] << "\n";
    os << "  queue_occupancy: " << queue_occupancies[i] << "\n";
    os << "  tokencount0: " << tokencounts0[i] << "\n";
    os << "  tokencount1: " << tokencounts1[i] << "\n";
  }
}

/**
 * Set the delay and period for the token hashers
 *
 * @param [in] delay The number of tokens before hashes are saved
 * @param [in] period The number of hashes to skip between saving. 0 means save
 * every hash
 */
void token_hashers_t::set_params(const uint64_t delay, const uint64_t period) {
#ifndef TOKENHASHMASTER_0_PRESENT
  std::cout << "token_hashers_t::set_params() was called but Token "
               "Hashers are not enabled in this build\n";
  return;
#endif
  write(trigger0, (delay & 0xffffffff));
  write(trigger1, ((delay >> 32) & 0xffffffff));

  write(period0, (period & 0xffffffff));
  write(period1, ((period >> 32) & 0xffffffff));
}

/**
 * Readout all token hashes from the FPGA using MMIO.
 * Returned value is a vector of vector of hashes. The index
 * to the outer vector is the number of the bridge. The bridge oder / numbering
 * is determined by scala at compiletime. Calling this twice in a row
 * will yield empty results the second time. This is because
 * the FIFO's are drained and so occupancy will return 0
 * @retval a vector of vector of hashes
 */
token_hasher_result_t token_hashers_t::live_get() {
  token_hasher_result_t ret;
  for (uint32_t i = 0; i < cnt; i++) {
    ret.push_back({});
    const uint32_t occ = occupancy(i);
    std::vector<uint32_t> &data = ret[i];
    data.reserve(occ);
    for (uint32_t j = 0; j < occ; j++) {
      const uint32_t h = read(queue_heads[i]);
      data.push_back(h);
    }
  }

  return ret;
}

void token_hashers_t::load_cache() {
  if (cached_results.size() == 0) {
    cached_results = live_get();
  }
}

void token_hashers_t::reset_cache() { cached_results.resize(0); }

/**
 * Same as get() however multiple calls will alwaus return the same data
 * Work is only done on the first call, subsequent calls return cached data
 * from the first time.
 */
token_hasher_result_t token_hashers_t::cached_get() {
  load_cache();
  return cached_results;
}

/**
 * Get a string of all the hashes. This calls cached_get() internally
 * @param [out] os a stream with human readable format. defaults to std::cout
 */
void token_hashers_t::get_string(std::ostream &os) {
  auto got = cached_get();
  uint32_t i = 0;
  for (const auto &row : got) {
    os << "Bridge " << i << ": " << bridge_names[i] << "->" << names[i] << "\n";
    for (const auto &data : row) {
      os << data << "\n";
    }
    i++;
  }
}

/**
 * Get a string with a CSV of all the hashes. This calls cached_get() internally
 * @param [out] os a stream with comma separated value formatted output
 */
void token_hashers_t::get_csv_string(std::ostream &os) {
  std::ostringstream oss;
  auto got = cached_get();
  uint32_t i = 0;
  os << "Hash index, Name, Hash\n";
  for (const auto &row : got) {
    const std::string name =
        (std::ostringstream()
         << "Bridge #" << i << " " << bridge_names[i] << "->" << names[i])
            .str();
    uint32_t j = 0;
    for (const auto &data : row) {
      os << j << "," << name << "," << data << "\n";
      j++;
    }
    i++;
  }
}

void token_hashers_t::write_csv_file(const std::string path) {
  std::ofstream file;
  file.open(path, std::ios::out);
  get_csv_string(file);
  file.close();
}

/**
 * Get results by index.
 * @retval a vector of all the hashes. these are cached results
 */
std::vector<uint32_t> token_hashers_t::get_idx(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to get_idx() is larger than count: " << cnt << "\n";
    exit(1);
  }

  load_cache();

  return cached_results[index];
}

/**
 * Get the FIFO occupancy for single bridge.
 * @param [in] index The index of the bridge
 * @retval The occupancy of the FIFO holding hashes
 */
uint32_t token_hashers_t::occupancy(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to occupany() is larger than count: " << cnt << "\n";
    exit(1);
  }

  return read(queue_occupancies[index]);
}

/**
 * Get the number of tokens a brige has seen. This number is not affected by
 * delay/period
 * @param [in] index The index of the bridge
 * @retval The number of tokens a bridge has seen
 */
uint64_t token_hashers_t::tokens(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to tokens() is larger than count: " << cnt << "\n";
    exit(1);
  }
  const uint64_t r0 = read(tokencounts0[index]);
  const uint64_t r1 = read(tokencounts1[index]);

  const uint64_t val = (r1 << 32) | r0;

  return val;
}

/**
 * Get the name of the bridge at by index. This number is not affected by
 * delay/period
 * @param [in] index The index of the bridge
 * @retval The number of tokens a bridge has seen
 */
std::tuple<std::string, std::string> token_hashers_t::name(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to name() is larger than count: " << cnt << "\n";
    exit(1);
  }

  return {bridge_names[index], names[index]};
}

size_t token_hashers_t::count() { return cnt; }

std::vector<size_t> token_hashers_t::search(const std::string &bridge_name,
                                            const std::string &signal_name) {
  std::vector<size_t> ret;
  for (uint32_t i = 0; i < cnt; i++) {
    if (bridge_names[i] == bridge_name && names[i] == signal_name) {
      ret.emplace_back(i);
    }
  }
  return ret;
}