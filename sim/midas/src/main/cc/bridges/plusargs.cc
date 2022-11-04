#ifdef PLUSARGSBRIDGEMODULE_struct_guard

#include "plusargs.h"
#include <iomanip>
#include <iostream>

/**
 * The default value is passed here (via the FireSim-generated.const.h).
 * All macros should be passed to this constructor.
 *
 * The plusargs string is parsed out from the name. Then the argv are searched
 * for any matches. The value of the first match is found, or else default.
 * The value is checked to reject any runtime values that are too big for the
 * bit_width
 * @param [in] args The argv as a vector
 * @param [in] mmio_addrs MMIO as provided by .h
 * @param [in] name_orig name string of the plusargs
 * @param [in] default_value The default value if no matching plusargs is
 * provided
 * @param [in] bit_width The number of bits
 * @param [in] slice_count The number of MMIO used to represent the value
 * @param [in] slice_addrs The MMIO addresses of the slices
 */
plusargs_t::plusargs_t(simif_t *sim,
                       std::vector<std::string> &args,
                       PLUSARGSBRIDGEMODULE_struct *mmio_addrs,
                       const std::string name_orig,
                       const std::string default_value,
                       const uint32_t bit_width,
                       const uint32_t slice_count,
                       const uint32_t *slice_addrs)
    : bridge_driver_t(sim), mmio_addrs(mmio_addrs), slice_count(slice_count),
      slice_addrs(slice_addrs, slice_addrs + slice_count) {
  std::string name = name_orig;

  // remove all leading white space
  while (name.at(0) == ' ') {
    name.erase(name.begin());
  }

  // remove one leading +, so we can add it back later
  if (name.at(0) == '+') {
    name.erase(name.begin());
  }

  const std::string delimiter = "=%d";
  auto found = name.find(delimiter);
  if (found == std::string::npos) {
    std::cout << "delimiter '" << delimiter
              << "' not found in the plusarg string '" << name_orig << "'\n";
    exit(1);
  }

  // the name without =%d and without +
  std::string base_name = name.substr(0, found);

  // the string we search for in the arg list
  std::string search = "+" + base_name + '=';

  std::string override_value;

  // bool arg_found = false;
  for (const auto &arg : args) {
    if (arg.find(search) == 0) {
      override_value = arg.substr(search.length());
      overriden = true;
      break;
    }
  }

  mpz_init(value);
  if (overriden) {
    mpz_set_str(value, override_value.c_str(), 10);
  } else {
    // use the default value from the .h
    mpz_set_str(value, default_value.c_str(), 10);
  }

  // check if the given or default value can fit in the width
  const size_t found_width = mpz_sizeinbase(value, 2);
  if (found_width > bit_width) {
    std::cout << "Value to wide for " << bit_width << " bits\n";
    exit(1);
  }
}

plusargs_t::~plusargs_t() { free(this->mmio_addrs); }

/**
 * Check if the overriden value was driven.
 * @retval true The value was overridden (a plusargs matched)
 * @retval false The value was default (no plusargs matched)
 */
bool plusargs_t::get_overridden() { return overriden; }

/**
 * Empty tick. It doesn't need to be called for proper operation
 */
void plusargs_t::tick() {}

/**
 * Get a slice's MMIO address by index
 * @params [in] idx The index of the slice
 * @returns the MMIO address
 */
uint32_t plusargs_t::slice_address(const uint32_t idx) {
  if (idx >= slice_count) {
    std::cout << "Index " << idx << " is larger than the number of slices "
              << slice_count << "\n";
    exit(1);
  }
  return slice_addrs[idx];
}

/**
 * Check if the overriden value was driven.
 * @retval true The value was overridden (a plusargs matched)
 * @retval false The value was default (no plusargs matched)
 */
void plusargs_t::init() {
  size_t size;
  const uint32_t *const slice_value =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);

  // write out slices that we have data for using mpz
  for (size_t i = 0; i < size; i++) {
    write(slice_address(i), slice_value[i]);
  }

  // write out the remaining with zeros
  for (size_t i = size; i < slice_count; i++) {
    write(slice_address(i), 0);
  }

  // after all registers are handled, set this
  write(this->mmio_addrs->initDone, 1);
}

#endif
