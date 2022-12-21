#include "simif_token_hashers.h"
#include "simif.h"

#include <iostream>
#include <sstream>
#include <fstream>

/**
 * The constructor for simif_token_hashers_t.
 *
 * @param [in] p A pointer to the parent instance of simif_t
 */
simif_token_hashers_t::simif_token_hashers_t(simif_t *p) : parent(p) {

#ifdef TOKENHASHMASTER_0_PRESENT
  TOKENHASHMASTER_0_substruct_create;
  trigger0 = TOKENHASHMASTER_0_substruct->triggerDelay0_TokenHashMaster;
  trigger1 = TOKENHASHMASTER_0_substruct->triggerDelay1_TokenHashMaster;
  period0 = TOKENHASHMASTER_0_substruct->triggerPeriod0_TokenHashMaster;
  period1 = TOKENHASHMASTER_0_substruct->triggerPeriod1_TokenHashMaster;
  free(TOKENHASHMASTER_0_substruct);

  cnt = TOKENHASH_COUNT;
  bridge_names.assign(TOKENHASH_BRIDGENAMES, TOKENHASH_BRIDGENAMES + cnt);
  names.assign(TOKENHASH_NAMES, TOKENHASH_NAMES + cnt);
  outputs.assign(TOKENHASH_OUTPUTS, TOKENHASH_OUTPUTS + cnt);
  queue_heads.assign(TOKENHASH_QUEUEHEADS, TOKENHASH_QUEUEHEADS + cnt);
  queue_occupancies.assign(TOKENHASH_QUEUEOCCUPANCIES,
                           TOKENHASH_QUEUEOCCUPANCIES + cnt);
  tokencounts0.assign(TOKENHASH_TOKENCOUNTS0, TOKENHASH_TOKENCOUNTS0 + cnt);
  tokencounts1.assign(TOKENHASH_TOKENCOUNTS1, TOKENHASH_TOKENCOUNTS1 + cnt);
#endif
}

/**
 * Print debug info about the MMIO internals
 */
void simif_token_hashers_t::info() {
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
void simif_token_hashers_t::set_params(const uint64_t delay,
                                       const uint64_t period) {
#ifndef TOKENHASHMASTER_0_PRESENT
  std::cout << "simif_token_hashers_t::set_params() was called but Token Hashers are not enabled in this build\n";
  return;
#endif  
  parent->write(trigger0, (delay & 0xffffffff));
  parent->write(trigger1, ((delay >> 32) & 0xffffffff));

  parent->write(period0, (period & 0xffffffff));
  parent->write(period1, ((period >> 32) & 0xffffffff));
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
token_hasher_result_t simif_token_hashers_t::get() {
  token_hasher_result_t ret;
  for (uint32_t i = 0; i < cnt; i++) {
    ret.push_back({});
    const uint32_t occ = occupancy(i);
    std::vector<uint32_t> &data = ret[i];
    data.reserve(occ);
    for (uint32_t j = 0; j < occ; j++) {
      const uint32_t h = parent->read(queue_heads[i]);
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
token_hasher_result_t simif_token_hashers_t::cached_get() {
  if(cached_results.size() == 0) {
    cached_results = get();
  }

  return cached_results;
}

/**
 * Get a string of all the hashes. This calls cached_get() internally
 * @retval a std::string with human readable output
 */
std::string simif_token_hashers_t::get_string() {
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
std::string simif_token_hashers_t::get_csv_string() {
  std::ostringstream oss;
  auto got = cached_get();
  uint32_t i = 0;
  oss << "Hash index, Name, Hash\n";
  std::string name;
  for (const auto &row : got) {
    name = (std::ostringstream() << "Bridge #" << i << " " << bridge_names[i] << "->" << names[i]).str();
    uint32_t j = 0;
    for (const auto &data : row) {
      oss << j << "," << name << "," << data << "\n";
      j++;
    }
    i++;
  }

  return oss.str();
}

void simif_token_hashers_t::write_csv_file(const std::string path) {
  std::ofstream file;
  file.open (path, std::ios::out);
  file << get_csv_string();
  file.close();
}

/**
 * Print all of the hashes to stdout, this calls get_string() internally
 */
void simif_token_hashers_t::print() { std::cout << get_string(); }

/**
 * Get the FIFO occupancy for single bridge.
 * @param [in] index The index of the bridge
 * @retval The occupancy of the FIFO holding hashes
 */
uint32_t simif_token_hashers_t::occupancy(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to occupany() is larger than count: " << cnt
              << "\n";
    exit(1);
  }

  return parent->read(queue_occupancies[index]);
}

/**
 * Get the number of tokens a brige has seen. This number is not affected by delay/period
 * @param [in] index The index of the bridge
 * @retval The number of tokens a bridge has seen
 */
uint64_t simif_token_hashers_t::tokens(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to tokens() is larger than count: " << cnt << "\n";
    exit(1);
  }
  uint64_t r0 = parent->read(tokencounts0[index]);
  uint64_t r1 = parent->read(tokencounts1[index]);

  uint64_t val = (r1 << 32) | r0;

  return val;
}

/**
 * Get the name of the bridge at by index. This number is not affected by delay/period
 * @param [in] index The index of the bridge
 * @retval The number of tokens a bridge has seen
 */
std::tuple<std::string, std::string> simif_token_hashers_t::name(const size_t index) {
  if (index >= cnt) {
    std::cerr << "index: " << index
              << " passed to name() is larger than count: " << cnt << "\n";
    exit(1);
  }

  return {bridge_names[index], names[index]};
}


size_t simif_token_hashers_t::count() {
  return cnt;
}
