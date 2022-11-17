#ifdef AUTOCOUNTERBRIDGEMODULE_struct_guard

#include "autocounter.h"

#include <cinttypes>
#include <iostream>
#include <limits.h>
#include <regex>
#include <stdio.h>
#include <string.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sys/mman.h>

autocounter_t::autocounter_t(simif_t *sim,
                             std::vector<std::string> &args,
                             AUTOCOUNTERBRIDGEMODULE_struct *mmio_addrs,
                             AddressMap addr_map,
                             const uint32_t event_count,
                             const char *const *event_types,
                             const uint32_t *event_widths,
                             const uint32_t *accumulator_widths,
                             const uint32_t *event_addr_hi,
                             const uint32_t *event_addr_lo,
                             const char *const *event_msgs,
                             const char *const *event_labels,
                             const char *const clock_domain_name,
                             const unsigned int clock_multiplier,
                             const unsigned int clock_divisor,
                             int autocounterno)
    : bridge_driver_t(sim), mmio_addrs(mmio_addrs), addr_map(addr_map),
      event_count(event_count),
      event_types(event_types, event_types + event_count),
      event_widths(event_widths, event_widths + event_count),
      accumulator_widths(accumulator_widths, accumulator_widths + event_count),
      event_addr_hi(event_addr_hi, event_addr_hi + event_count),
      event_addr_lo(event_addr_lo, event_addr_lo + event_count),
      event_msgs(event_msgs, event_msgs + event_count),
      event_labels(event_labels, event_labels + event_count),
      clock_info(clock_domain_name, clock_multiplier, clock_divisor) {

  this->autocounter_filename = "AUTOCOUNTER";
  const char *autocounter_filename_in = NULL;
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

autocounter_t::~autocounter_t() { free(this->mmio_addrs); }

void autocounter_t::init() {
  // Decrement the readrate by one to simplify the HW a little bit
  write(addr_map.w_registers.at("readrate_low"),
        (readrate - 1) & ((1ULL << 32) - 1));
  write(addr_map.w_registers.at("readrate_high"), this->readrate >> 32);
  write(mmio_addrs->init_done, 1);
}

std::string
replace_all(std::string str, const std::string &from, const std::string &to) {
  size_t start_pos = 0;
  while ((start_pos = str.find(from, start_pos)) != std::string::npos) {
    str.replace(start_pos, from.length(), to);
    start_pos += to.length();
  }
  return str;
}

// Since description fields may have commas, quote them to prevent introducing
// extra delimiters. Note, the standard way to escape double-quotes is to double
// them (" -> "")
// https://stackoverflow.com/questions/17808511/properly-escape-a-double-quote-in-csv
std::string quote_csv_element(std::string str) {
  std::string quoted = replace_all(str, "\"", "\"\"");
  return '"' + quoted + '"';
}

template <typename T>
void write_header_array_to_csv(std::ofstream &f,
                               std::vector<T> &row,
                               std::string first_column) {
  f << first_column << ",";
  assert(!row.empty());
  for (auto it = row.begin(); it != row.end(); it++) {
    f << *it;
    if ((it + 1) != row.end()) {
      f << ",";
    } else {
      f << std::endl;
    }
  }
}

void autocounter_t::emit_autocounter_header() {

  autocounter_file << "version," << autocounter_csv_format_version << std::endl;
  autocounter_file << clock_info.as_csv_row();

  auto quoted_descriptions = std::vector<std::string>();
  for (auto &desc : event_msgs) {
    quoted_descriptions.push_back(quote_csv_element(desc));
  }

  write_header_array_to_csv(autocounter_file, event_labels, "label");
  write_header_array_to_csv(
      autocounter_file, quoted_descriptions, "\"description\"");
  write_header_array_to_csv(autocounter_file, event_types, "type");
  write_header_array_to_csv(autocounter_file, event_widths, "event width");
  write_header_array_to_csv(
      autocounter_file, accumulator_widths, "accumulator width");
}

bool autocounter_t::drain_sample() {
  bool bridge_has_sample = read(addr_map.r_registers.at("countersready"));
  if (bridge_has_sample) {
    cur_cycle_base_clock += readrate_base_clock;
    autocounter_file << cur_cycle_base_clock << ",";
    for (size_t idx = 0; idx < event_count; idx++) {
      uint64_t counter_val = ((uint64_t)(read(event_addr_hi[idx]))) << 32;
      counter_val |= read(event_addr_lo[idx]);
      autocounter_file << counter_val;

      if (idx < (event_count - 1)) {
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

#endif // AUTOCOUNTERBRIDGEMODULE_struct_guard
