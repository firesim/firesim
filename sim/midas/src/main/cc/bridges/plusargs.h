
#ifndef __PLUSARGS_H
#define __PLUSARGS_H
// See LICENSE for license details.

#ifdef PLUSARGSBRIDGEMODULE_struct_guard

#include "bridges/bridge_driver.h"
#include <gmp.h>
#include <string.h>

#define INSTANTIATE_PLUSARGS(FUNC, IDX)                                        \
  FUNC(new plusargs_t(                                                         \
      this,                                                                    \
      args,                                                                    \
      []() {                                                                   \
        PLUSARGSBRIDGEMODULE_##IDX##_substruct_create;                         \
        return PLUSARGSBRIDGEMODULE_##IDX##_substruct;                         \
      }(),                                                                     \
      PLUSARGSBRIDGEMODULE_##IDX##_name,                                       \
      PLUSARGSBRIDGEMODULE_##IDX##_default,                                    \
      PLUSARGSBRIDGEMODULE_##IDX##_width,                                      \
      PLUSARGSBRIDGEMODULE_##IDX##_slice_count,                                \
      PLUSARGSBRIDGEMODULE_##IDX##_slice_addrs));

/**
 * @brief Plusargs Bridge Driver class
 *
 * This Bridge Driver talks to a plusargs Bridge. This class will determine
 * if the default, or overriden plusarg value shoud be driven.
 *
 * Arbirrary wide bit widths are supported via
 * MPFR.
 */
class plusargs_t : public bridge_driver_t {
public:
  plusargs_t(simif_t *sim,
             std::vector<std::string> &args,
             PLUSARGSBRIDGEMODULE_struct *mmio_addrs,
             const std::string name,
             const std::string default_value,
             const uint32_t bit_width,
             const uint32_t slice_count,
             const uint32_t *slice_addrs);
  ~plusargs_t();
  virtual void init();
  virtual void tick();
  virtual void finish(){};
  virtual bool terminate() { return false; };
  virtual int exit_code() { return 0; };
  uint32_t slice_address(const uint32_t idx);
  bool get_overridden();

private:
  PLUSARGSBRIDGEMODULE_struct *mmio_addrs;
  mpz_t value;            // either the default or the plus arg value
  bool overriden = false; // true if the plusarg was found and parsed
  const uint32_t slice_count = 0;
  const std::vector<uint32_t> slice_addrs;
};
#endif // PLUSARGSBRIDGEMODULE_struct_guard
#endif //__PLUSARGS_H
