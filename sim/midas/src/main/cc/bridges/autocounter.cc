
#include "autocounter.h"

#include <cassert>
#include <cinttypes>
#include <climits>
#include <cstdio>
#include <cstring>

#include <iostream>
#include <regex>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sys/mman.h>

char autocounter_t::KIND;

autocounter_t::autocounter_t(simif_t &sim,
                             AddressMap &&addr_map,
                             const AUTOCOUNTERBRIDGEMODULE_struct &mmio_addrs,
                             unsigned autocounterno,
                             const std::vector<std::string> &args,
                             const std::vector<Counter> &counters,
                             const ClockInfo &clock_info)
    : bridge_driver_t(sim, &KIND), mmio_addrs(mmio_addrs),
      addr_map(std::move(addr_map)), counters(counters),
      clock_info(clock_info) {

  this->autocounter_filename = "AUTOCOUNTER";
  const char *autocounter_filename_in = nullptr;
  std::string readrate_arg = std::string("+autocounter-readrate=");
  std::string filename_arg = std::string("+autocounter-filename-base=");

  for (auto &arg : args) {
    if (arg.find(readrate_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + readrate_arg.length();
      uint64_t base_cycles = atol(str);
      this->readrate_base_clock = base_cycles;
      this->readrate =
          this->clock_info.to_local_cycles(this->readrate_base_clock);
      // TODO: Fix this in the bridge by not sampling with a fixed frequency
      if (this->clock_info.to_base_cycles(this->readrate) !=
          this->readrate_base_clock) {
        fprintf(stderr,
                "[AutoCounter] Warning: requested sample rate of %" PRIu64
                " [base] cycles does not map to a whole number\n\
of cycles in clock domain: %s, (%d/%d) of base clock.\n\
See the AutoCounter documentation on Reset And Timing Considerations for discussion.\n",
                this->readrate_base_clock,
                this->clock_info.domain_name,
                this->clock_info.multiplier,
                this->clock_info.divisor);
        fprintf(stderr,
                "[AutoCounter] Workaround: Pick a sample rate that is "
                "divisible by all clock divisors.\n");
      }
    }
    if (arg.find(filename_arg) == 0) {
      autocounter_filename_in =
          const_cast<char *>(arg.c_str()) + filename_arg.length();
      this->autocounter_filename = std::string(autocounter_filename_in) +
                                   std::to_string(autocounterno) + ".csv";
    }
  }

  autocounter_file.open(this->autocounter_filename, std::ofstream::out);
  if (!autocounter_file.is_open()) {
    throw std::runtime_error("Could not open output file: " +
                             this->autocounter_filename);
  }
  emit_autocounter_header();
}

autocounter_t::~autocounter_t() = default;

void autocounter_t::init() {
  // Decrement the readrate by one to simplify the HW a little bit
  write(addr_map.w_registers.at("readrate_low"),
        (readrate - 1) & ((1ULL << 32) - 1));
  write(addr_map.w_registers.at("readrate_high"), this->readrate >> 32);
  write(mmio_addrs.init_done, 1);
}

std::string replace_all(const std::string &str,
                        const std::string &from,
                        const std::string &to) {
  std::string result = str;
  size_t start_pos = 0;
  while ((start_pos = result.find(from, start_pos)) != std::string::npos) {
    result.replace(start_pos, from.length(), to);
    start_pos += to.length();
  }
  return result;
}

// Since description fields may have commas, quote them to prevent introducing
// extra delimiters. Note, the standard way to escape double-quotes is to double
// them (" -> "")
// https://stackoverflow.com/questions/17808511/properly-escape-a-double-quote-in-csv
std::string quote_csv_element(const std::string &str) {
  std::string quoted = replace_all(str, "\"", "\"\"");
  return '"' + quoted + '"';
}

template <typename T>
void write_header_array_to_csv(
    std::ofstream &os,
    std::vector<autocounter_t::Counter> &counters,
    const std::string &first_column,
    const std::function<T(const autocounter_t::Counter &counter)> &f) {
  assert(!counters.empty());

  os << first_column << ",";
  for (auto it = counters.begin(); it != counters.end(); it++) {
    os << f(*it);
    if ((it + 1) != counters.end()) {
      os << ",";
    } else {
      os << std::endl;
    }
  }
}

void autocounter_t::emit_autocounter_header() {

  autocounter_file << "version," << autocounter_csv_format_version << std::endl;
  autocounter_file << clock_info.as_csv_row();

  write_header_array_to_csv<std::string>(autocounter_file,
                                         counters,
                                         "label",
                                         [](auto &p) { return p.event_label; });

  write_header_array_to_csv<std::string>(
      autocounter_file, counters, "\"description\"", [](auto &p) {
        return quote_csv_element(p.event_msg);
      });

  write_header_array_to_csv<std::string>(
      autocounter_file, counters, "type", [](auto &p) { return p.type; });

  write_header_array_to_csv<uint32_t>(
      autocounter_file, counters, "event width", [](auto &p) -> uint32_t {
        return p.bit_width;
      });

  write_header_array_to_csv<uint32_t>(
      autocounter_file, counters, "accumulator width", [](auto &p) -> uint32_t {
        return p.accumulator_width;
      });
}

bool autocounter_t::drain_sample() {
  bool bridge_has_sample = read(addr_map.r_registers.at("countersready"));
  if (bridge_has_sample) {
    cur_cycle_base_clock += readrate_base_clock;
    autocounter_file << cur_cycle_base_clock << ",";
    for (size_t idx = 0; idx < counters.size(); idx++) {
      uint32_t addr_hi = counters[idx].event_addr_hi;
      uint32_t addr_lo = counters[idx].event_addr_lo;

      uint64_t counter_val = ((uint64_t)(read(addr_hi))) << 32;
      counter_val |= read(addr_lo);
      autocounter_file << counter_val;

      if (idx < (counters.size() - 1)) {
        autocounter_file << ",";
      } else {
        autocounter_file << std::endl;
      }
    }
    write(addr_map.w_registers.at("readdone"), 1);
  }
  return bridge_has_sample;
}

void autocounter_t::tick() { drain_sample(); }

void autocounter_t::finish() {
  while (drain_sample())
    ;
}
