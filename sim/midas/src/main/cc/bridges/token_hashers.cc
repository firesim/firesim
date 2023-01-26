#include "token_hashers.h"
#include "core/simif.h"

#include <fstream>
#include <iostream>
#include <sstream>


char token_hashers_t::KIND;

/**
 * The constructor for token_hashers_t.
 *
 */
token_hashers_t::token_hashers_t(
    simif_t &sim,
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
void token_hashers_t::info() {
  std::cout << "trigger0 " << trigger0 << "\n";
  std::cout << "trigger1 " << trigger1 << "\n";
  std::cout << "period0 " << period0 << "\n";
  std::cout << "period1 " << period1 << "\n";

  for (uint32_t i = 0; i < cnt; i++) {
    std::cout << "i: " << i << "\n";
    std::cout << "  bridge: " << bridge_names[i] << "\n";
    std::cout << "  name: " << names[i] << "\n";
    std::cout << "  direction: " << (outputs[i] ? "Output" : "Input") << "\n";
    std::cout << "  queue_head: " << queue_heads[i] << "\n";
    std::cout << "  queue_occupancy: " << queue_occupancies[i] << "\n";
    std::cout << "  tokencount0: " << tokencounts0[i] << "\n";
    std::cout << "  tokencount1: " << tokencounts1[i] << "\n";
  }
}

/**
 * Set the delay and period for the token hashers
 *
 * @param [in] delay The number of tokens before hashes are saved
 * @param [in] period The number of hashes to skip between saving. 0 means save
 * every hash
 */
void token_hashers_t::set_params(const uint64_t delay,
                                       const uint64_t period) {
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
token_hasher_result_t token_hashers_t::get() {
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

/**
 * Same as get() however multiple calls will alwaus return the same data
 * Work is only done on the first call, subsequent calls return cached data
 * from the first time.
 */
token_hasher_result_t token_hashers_t::cached_get() {
  if (cached_results.size() == 0) {
    cached_results = get();
  }

  return cached_results;
}

/**
 * Get a string of all the hashes. This calls cached_get() internally
 * @retval a std::string with human readable output
 */
std::string token_hashers_t::get_string() {
  std::ostringstream oss;
  auto got = cached_get();
  uint32_t i = 0;
  for (const auto &row : got) {
    oss << "Bridge " << i << ": " << bridge_names[i] << "->" << names[i]
        << "\n";
    for (const auto &data : row) {
      oss << data << "\n";
    }
    i++;
  }

  return oss.str();
}

/**
 * Get a string with a CSV of all the hashes. This calls cached_get() internally
 * @retval a std::string with human readable output
 */
std::string token_hashers_t::get_csv_string() {
  std::ostringstream oss;
  auto got = cached_get();
  uint32_t i = 0;
  oss << "Hash index, Name, Hash\n";
  std::string name;
  for (const auto &row : got) {
    name = (std::ostringstream()
            << "Bridge #" << i << " " << bridge_names[i] << "->" << names[i])
               .str();
    uint32_t j = 0;
    for (const auto &data : row) {
      oss << j << "," << name << "," << data << "\n";
      j++;
    }
    i++;
  }

  return oss.str();
}

void token_hashers_t::write_csv_file(const std::string path) {
  std::ofstream file;
  file.open(path, std::ios::out);
  file << get_csv_string();
  file.close();
}

/**
 * Print all of the hashes to stdout, this calls get_string() internally
 */
void token_hashers_t::print() { std::cout << get_string(); }

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
  uint64_t r0 = read(tokencounts0[index]);
  uint64_t r1 = read(tokencounts1[index]);

  uint64_t val = (r1 << 32) | r0;

  return val;
}

/**
 * Get the name of the bridge at by index. This number is not affected by
 * delay/period
 * @param [in] index The index of the bridge
 * @retval The number of tokens a bridge has seen
 */
std::tuple<std::string, std::string>
token_hashers_t::name(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to name() is larger than count: " << cnt << "\n";
    exit(1);
  }

  return {bridge_names[index], names[index]};
}

size_t token_hashers_t::count() { return cnt; }
