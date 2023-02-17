// See LICENSE for license details.

#ifndef __CLOCK_INFO_H
#define __CLOCK_INFO_H

#include <ostream>

/**
 * Stores Bridge clock domain information and provides methods for converting
 * from base clock cycles to cycles in the Bridge's clock domain ("local"
 * cycles).
 */
class ClockInfo {
public:
  ClockInfo(const char *const clock_domain_name,
            const unsigned int clock_multiplier,
            const unsigned int clock_divisor)
      : domain_name(clock_domain_name), multiplier(clock_multiplier),
        divisor(clock_divisor) {}

  const char *const domain_name;
  const unsigned int multiplier;
  const unsigned int divisor;

  // NB: These truncate and may be inexact, use with care
  uint64_t to_local_cycles(uint64_t base_clock_cycles) {
    return (base_clock_cycles * multiplier) / divisor;
  };

  uint64_t to_base_cycles(uint64_t local_clock_cycles) {
    return (local_clock_cycles * divisor) / multiplier;
  };

  // Capture clock domain info in a string that can be prepended to
  // driver-generated files so the user can disambiguate between them
  std::string file_header() {
    char buf[200];
    sprintf(buf,
            "# Clock Domain: %s, Relative Frequency: %d/%d of Base Clock\n",
            domain_name,
            multiplier,
            divisor);
    return std::string(buf);
  };

  void emit_file_header(std::ostream &os) { os << file_header(); };

  std::string as_csv_row() {
    char buf[200];
    sprintf(buf,
            "Clock Domain Name, %s, Base Multiplier, %d, Base Divisor, %d\n",
            domain_name,
            multiplier,
            divisor);
    return std::string(buf);
  };
};

#endif // __CLOCK_INFO_H
