// See LICENSE for license details.

#ifndef __CLOCK_INFO_H
#define __CLOCK_INFO_H

#include <ostream>

class ClockInfo
{
public:
  ClockInfo(
    const char* const  clock_domain_name,
    const unsigned int clock_multiplier,
    const unsigned int clock_divisor):
      clock_domain_name(clock_domain_name),
      clock_multiplier(clock_multiplier),
      clock_divisor(clock_divisor) {};

  const char* const  clock_domain_name;
  const unsigned int clock_multiplier;
  const unsigned int clock_divisor;

  uint64_t to_local_cycles(uint64_t base_clock_cycles) {
    return (base_clock_cycles * clock_multiplier) / clock_divisor;
  };

  uint64_t to_base_cycles(uint64_t local_clock_cycles) {
    return (local_clock_cycles * clock_divisor) / clock_multiplier;
  };

  void emit_file_header(std::ostream& os) {
    os << "# Clock Domain: " << std::string(clock_domain_name);
    os << ", Relative Frequency: " << clock_multiplier << "/" << clock_divisor;
    os << " of Base Clock" << std::endl;
  }

};

#endif // __CLOCK_INFO_H
