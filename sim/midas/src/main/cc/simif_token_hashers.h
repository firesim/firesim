// See LICENSE for license details.

#ifndef __SIMIF_TOKEN_HASHERS_H
#define __SIMIF_TOKEN_HASHERS_H

#include <vector>

// forward declare
class simif_t;

typedef std::vector<std::vector<uint32_t>> token_hasher_result_t;

/** \class simif_token_hashers_t
 *
 *  @brief Class for controlling Token Hashers
 *
 *  Token Hashers allow debugging run-to-run non determinism
 */
class simif_token_hashers_t {
private:
  simif_t *parent = 0;

public:
  simif_token_hashers_t(simif_t *p);
  void info();
  void set_params(const uint64_t delay, const uint64_t period);
  token_hasher_result_t get();
  token_hasher_result_t cached_get();
  std::string get_string();
  std::string get_csv_string();
  void write_csv_file(const std::string path);
  void print();
  uint32_t occupancy(const size_t index);
  uint64_t tokens(const size_t index);
  std::tuple<std::string, std::string> name(const size_t index);
  size_t count();
  token_hasher_result_t cached_results;
  

  uint32_t trigger0;
  uint32_t trigger1;
  uint32_t period0;
  uint32_t period1;

  size_t cnt;
  std::vector<std::string> bridge_names;
  std::vector<std::string> names;
  std::vector<bool> outputs;
  std::vector<uint32_t> queue_heads;
  std::vector<uint32_t> queue_occupancies;
  std::vector<uint32_t> tokencounts0;
  std::vector<uint32_t> tokencounts1;
};

#endif
