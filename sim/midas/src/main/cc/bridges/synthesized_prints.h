#ifndef __SYNTHESIZED_PRINTS_H
#define __SYNTHESIZED_PRINTS_H

#include <fstream>
#include <gmp.h>
#include <iostream>
#include <vector>

#include "core/bridge_driver.h"
#include "core/clock_info.h"

struct print_vars_t {
  std::vector<mpz_t *> data;
  ~print_vars_t() {
    for (auto &e : data) {
      mpz_clear(*e);
      free(e);
    }
  }
};

struct PRINTBRIDGEMODULE_struct {
  uint64_t startCycleL;
  uint64_t startCycleH;
  uint64_t endCycleL;
  uint64_t endCycleH;
  uint64_t doneInit;
  uint64_t flushNarrowPacket;
};

class synthesized_prints_t : public streaming_bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  synthesized_prints_t(simif_t &sim,
                       StreamEngine &stream,
                       const std::vector<std::string> &args,
                       const PRINTBRIDGEMODULE_struct &mmio_addrs,
                       unsigned int print_count,
                       unsigned int token_bytes,
                       unsigned int idle_cycles_mask,
                       const unsigned int *print_offsets,
                       const char *const *format_strings,
                       const unsigned int *argument_counts,
                       const unsigned int *argument_widths,
                       unsigned int stream_idx,
                       unsigned int stream_depth,
                       const char *clock_domain_name,
                       unsigned int clock_multiplier,
                       unsigned int clock_divisor,
                       int printno);
  ~synthesized_prints_t() override;
  void init() override;
  void tick() override;
  bool terminate() override { return false; };
  int exit_code() override { return 0; };
  void flush();
  void finish() override { flush(); };

private:
  const PRINTBRIDGEMODULE_struct mmio_addrs;
  const unsigned int print_count;
  const unsigned int token_bytes;
  const unsigned int idle_cycles_mask;
  const unsigned int *print_offsets;
  const char *const *format_strings;
  const unsigned int *argument_counts;
  const unsigned int *argument_widths;
  const unsigned int stream_idx;
  const unsigned int stream_depth;
  ClockInfo clock_info;
  const int printno;

  // Stream batching parameters
  static constexpr size_t beat_bytes = STREAM_WIDTH_BYTES;
  // The number of stream beats to pull off the FPGA on each invocation of
  // tick() This will be set based on the ratio of token_size :
  // desired_batch_beats
  size_t batch_beats;
  // This will be modified to be a multiple of the token size
  const size_t desired_batch_beats = stream_depth / 2;

  // Used to define the boundaries in the batch buffer at which we'll
  // initalize GMP types
  using gmp_align_t = uint64_t;
  const size_t gmp_align_bits = sizeof(gmp_align_t) * 8;

  // +arg driven members
  std::ofstream printfile; // Used only if the +print-file arg is provided
  std::string default_filename = "synthesized-prints.out";

  std::ostream *printstream; // Is set to std::cerr otherwise
  uint64_t start_cycle,
      end_cycle; // Bounds between which prints will be emitted
  uint64_t current_cycle = 0;
  bool human_readable = true;
  bool print_cycle_prefix = true;

  std::vector<std::vector<size_t>> widths;
  std::vector<size_t> sizes;
  std::vector<print_vars_t *> masks;

  std::vector<size_t> aligned_offsets; // Aligned to gmp_align_t
  std::vector<size_t> bit_offset;

  bool current_print_enabled(gmp_align_t *buf, size_t offset);
  size_t process_tokens(size_t beats, size_t minimum_batch_beats);
  void show_prints(char *buf);
  void print_format(const char *fmt, print_vars_t *vars, print_vars_t *masks);
  // Returns the number of beats available, once two successive reads return the
  // same value
  int beats_avaliable_stable();
};

#endif //__SYNTHESIZED_PRINTS_H
