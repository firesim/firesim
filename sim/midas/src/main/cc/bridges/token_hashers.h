// See LICENSE for license details.

#ifndef __TOKEN_HASHERS_H
#define __TOKEN_HASHERS_H

#include "core/bridge_driver.h"
#include <cstddef>
#include <iostream>
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
                  uint32_t cnt,
                  const char *const *bridge_names,
                  const char *const *names,
                  const uint32_t *outputs,
                  const uint32_t *queue_heads,
                  const uint32_t *queue_occupancies,
                  const uint32_t *tokencounts0,
                  const uint32_t *tokencounts1);
  ~token_hashers_t() override;
  void init() override;
  void tick() override {}
  void finish() override {}
  bool terminate() override { return false; };
  int exit_code() override { return 0; };

  void info(std::ostream &os = std::cout);
  void set_params(uint64_t delay, uint64_t period);

private:
  token_hasher_result_t live_get();

public:
  void load_cache();
  void reset_cache();
  token_hasher_result_t cached_get();
  void get_string(std::ostream &os = std::cout);
  void get_csv_string(std::ostream &os = std::cout);
  void write_csv_file(const std::string path);
  std::vector<uint32_t> get_idx(size_t index);
  uint32_t occupancy(size_t index);
  uint64_t tokens(size_t index);
  std::tuple<std::string, std::string> name(size_t index);
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
