
#ifndef __PLUSARGS_H
#define __PLUSARGS_H
// See LICENSE for license details.

#include "core/bridge_driver.h"
#include <gmp.h>
#include <string.h>
#include <string_view>

struct PLUSARGSBRIDGEMODULE_struct {
  uint64_t initDone;
  uint64_t queueHead_outChannel;
  uint64_t queueOccupancy_outChannel;
  uint64_t tokenCount0_outChannel;
  uint64_t tokenCount1_outChannel;
} PLUSARGSBRIDGEMODULE_struct;

#ifdef asdfasdfPLUSARGSBRIDGEMODULE_checks
PLUSARGSBRIDGEMODULE_checks;
#endif // PLUSARGSBRIDGEMODULE_checks

#define INSTANTIATE_PLUSARGS(FUNC, IDX)                                        \
  FUNC(new plusargs_t(simif,                                                   \
                      args,                                                    \
                      PLUSARGSBRIDGEMODULE_##IDX##_substruct_create,           \
                      PLUSARGSBRIDGEMODULE_##IDX##_name,                       \
                      PLUSARGSBRIDGEMODULE_##IDX##_default,                    \
                      PLUSARGSBRIDGEMODULE_##IDX##_width,                      \
                      PLUSARGSBRIDGEMODULE_##IDX##_slice_count,                \
                      PLUSARGSBRIDGEMODULE_##IDX##_slice_addrs));

/**
 * @brief PlusArgs Bridge Driver class
 *
 * This Bridge Driver talks to a PlusArgs Bridge. This class will determine
 * if the default, or overridden PlusArg value shoud be driven.
 *
 * Arbitrary wide bit widths are supported via
 * MPFR.
 */
class plusargs_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  plusargs_t(simif_t &sim,
             const PLUSARGSBRIDGEMODULE_struct &mmio_addrs,
             unsigned index,
             const std::vector<std::string> &args,
             std::string_view name,
             const char *default_value,
             uint32_t bit_width,
             const std::vector<uint32_t> &slice_addrs);
  ~plusargs_t() override;

  void init() override;
  uint32_t slice_address(uint32_t idx);

  bool get_overridden();

private:
  const PLUSARGSBRIDGEMODULE_struct mmio_addrs;
  mpz_t value;            // either the default or the PlusArg value
  bool overriden = false; // true if the PlusArg was found and parsed
  const std::vector<uint32_t> slice_addrs;
};

#endif //__PLUSARGS_H
