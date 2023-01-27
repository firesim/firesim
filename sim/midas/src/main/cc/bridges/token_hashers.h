// See LICENSE for license details.

#ifndef __TOKEN_HASHERS_H
#define __TOKEN_HASHERS_H

#include "core/bridge_driver.h"
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

// forward declare
class simif_t;

class XORHash32 {
public:
  uint32_t get();
  uint32_t next(const uint32_t input);
  uint32_t lfsr = 0;
};

typedef std::vector<std::vector<uint32_t>> token_hasher_result_t;

/** \class token_hashers_t
 *
 *  @brief Class for controlling Token Hashers
 *
 *  Token Hashers allow debugging run-to-run non determinism
 */
class token_hashers_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  token_hashers_t(simif_t &p,
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
  ~token_hashers_t() override;
  void init() override;
  void tick() override {}
  void finish() override {}
  bool terminate() override { return false; };
  int exit_code() override { return 0; };

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
  std::vector<size_t> search(const std::string &bridge_name,
                             const std::string &signal_name);
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
