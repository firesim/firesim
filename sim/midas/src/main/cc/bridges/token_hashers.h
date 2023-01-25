// See LICENSE for license details.

#ifndef __TOKEN_HASHERS_H
#define __TOKEN_HASHERS_H

#include <cstddef>
#include <string>
#include <vector>

typedef struct TOKENHASHMASTER_struct {
  unsigned long triggerDelay0_TokenHashMaster;
  unsigned long triggerDelay1_TokenHashMaster;
  unsigned long triggerPeriod0_TokenHashMaster;
  unsigned long triggerPeriod1_TokenHashMaster;
} TOKENHASHMASTER_struct;

#ifdef TOKENHASHMASTER_checks
TOKENHASHMASTER_checks;
#endif // TOKENHASHMASTER_checks

#define INSTANTIATE_TOKENHASHMASTER(FUNC, IDX)                                 \
  FUNC(new token_hashers_t(simif,                                        \
                                 args,                                         \
                                 TOKENHASHMASTER_##IDX##_substruct_create,     \
                                 TOKENHASH_COUNT,                              \
                                 TOKENHASH_BRIDGENAMES,                        \
                                 TOKENHASH_NAMES,                              \
                                 TOKENHASH_OUTPUTS,                            \
                                 TOKENHASH_QUEUEHEADS,                         \
                                 TOKENHASH_QUEUEOCCUPANCIES,                   \
                                 TOKENHASH_TOKENCOUNTS0,                       \
                                 TOKENHASH_TOKENCOUNTS1));

// forward declare
class simif_t;

typedef std::vector<std::vector<uint32_t>> token_hasher_result_t;

/** \class token_hashers_t
 *
 *  @brief Class for controlling Token Hashers
 *
 *  Token Hashers allow debugging run-to-run non determinism
 */
class token_hashers_t {
private:
  simif_t *parent = 0;

public:
  token_hashers_t(simif_t *p,
                        const std::vector<std::string> &args,
                        const TOKENHASHMASTER_struct &s,
                        const uint32_t cnt,
                        const char *const *bridge_names,
                        const char *const *names,
                        const uint32_t *const outputs,
                        const uint32_t *const queue_heads,
                        const uint32_t *const queue_occupancies,
                        const uint32_t *const tokencounts0,
                        const uint32_t *const tokencounts1);
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
