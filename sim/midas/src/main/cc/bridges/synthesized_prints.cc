#include "synthesized_prints.h"

#include <cassert>

#include <iomanip>
#include <iostream>

char synthesized_prints_t::KIND;

synthesized_prints_t::synthesized_prints_t(
    simif_t &sim,
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
    const char *const clock_domain_name,
    const unsigned int clock_multiplier,
    const unsigned int clock_divisor,
    int printno)
    : streaming_bridge_driver_t(sim, stream, &KIND), mmio_addrs(mmio_addrs),
      print_count(print_count), token_bytes(token_bytes),
      idle_cycles_mask(idle_cycles_mask), print_offsets(print_offsets),
      format_strings(format_strings), argument_counts(argument_counts),
      argument_widths(argument_widths), stream_idx(stream_idx),
      stream_depth(stream_depth),
      clock_info(clock_domain_name, clock_multiplier, clock_divisor),
      printno(printno) {
  assert((token_bytes & (token_bytes - 1)) == 0);
  assert(print_count > 0);

  auto printfilename = default_filename + std::to_string(printno);

  this->start_cycle = 0;
  this->end_cycle = -1ULL;

  std::string num_equals = std::to_string(printno) + std::string("=");
  // PlusArgs are shared across all Bridge Driver instances
  // The file into which to emit captured prinfs. This is suffixed with the
  // driver number
  std::string printfile_arg = std::string("+print-file=");
  // The cycle at which to start printing in base clock cycles
  std::string printstart_arg = std::string("+print-start=");
  // The cycle at which to stop printing in base clock cycles
  std::string printend_arg = std::string("+print-end=");
  // Does not format the printfs, before writing them to file
  std::string binary_arg = std::string("+print-binary");
  // Removes the cycle prefix from human-readable output
  std::string cycleprefix_arg = std::string("+print-no-cycle-prefix");

  // Choose a multiple of token_bytes for the batch size
  if (((beat_bytes * desired_batch_beats) % token_bytes) != 0) {
    assert(token_bytes % beat_bytes == 0);
    auto beats_per_token = token_bytes / beat_bytes;
    this->batch_beats =
        (desired_batch_beats / beats_per_token) * beats_per_token;
  } else {
    this->batch_beats = desired_batch_beats;
  }

  for (auto arg : args) {
    if (arg.find(printfile_arg) == 0) {
      printfilename =
          arg.erase(0, printfile_arg.length()) + std::to_string(printno);
    }
    if (arg.find(printstart_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + printstart_arg.length();
      this->start_cycle = this->clock_info.to_local_cycles(atol(str));
    }
    if (arg.find(printend_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + printend_arg.length();
      this->end_cycle = this->clock_info.to_local_cycles(atol(str));
    }
    if (arg.find(binary_arg) == 0) {
      human_readable = false;
    }
    if (arg.find(cycleprefix_arg) == 0) {
      print_cycle_prefix = false;
    }
  }
  current_cycle =
      start_cycle; // We won't receive tokens until start_cycle; so fast-forward

  this->printfile.open(printfilename.c_str(),
                       std::ios_base::out | std::ios_base::binary);
  if (!this->printfile.is_open()) {
    fprintf(
        stderr, "Could not open print log file: %s\n", printfilename.c_str());
    abort();
  }

  this->printstream = &(this->printfile);
  this->clock_info.emit_file_header(*(this->printstream));

  widths.resize(print_count);
  // Used to reconstruct the relative position of arguments in the flattened
  // argument_widths array
  size_t arg_base_offset = 0;
  size_t print_bit_offset =
      1; // The lsb of the current print in the packed token

  for (size_t p_idx = 0; p_idx < print_count; p_idx++) {

    auto print_args = new print_vars_t;
    size_t print_width = 1; // A running total of argument widths for this
                            // print, including an enable bit

    // Iterate through the arguments for this print
    for (size_t arg_idx = 0; arg_idx < argument_counts[p_idx]; arg_idx++) {
      size_t arg_width = argument_widths[arg_base_offset + arg_idx];
      widths[p_idx].push_back(arg_width);

      mpz_t *mask = (mpz_t *)malloc(sizeof(mpz_t));
      // Below is equivalent to  *mask = (1 << arg_width) - 1
      mpz_init(*mask);
      mpz_set_ui(*mask, 1);
      mpz_mul_2exp(*mask, *mask, arg_width);
      mpz_sub_ui(*mask, *mask, 1);

      print_args->data.push_back(mask);
      print_width += arg_width;
    }

    size_t aligned_offset = print_bit_offset / gmp_align_bits;
    size_t aligned_msw = (print_width + print_bit_offset) / gmp_align_bits;
    size_t rounded_size = aligned_msw - aligned_offset + 1;

    arg_base_offset += argument_counts[p_idx];
    masks.push_back(print_args);
    sizes.push_back(rounded_size);
    aligned_offsets.push_back(aligned_offset);
    bit_offset.push_back(print_bit_offset % gmp_align_bits);

    print_bit_offset += print_width;
  }
};

synthesized_prints_t::~synthesized_prints_t() {
  for (size_t i = 0; i < print_count; i++) {
    delete masks[i];
  }
}

void synthesized_prints_t::init() {
  // Set the bounds in the widget
  write(mmio_addrs.startCycleL, this->start_cycle);
  write(mmio_addrs.startCycleH, this->start_cycle >> 32);
  write(mmio_addrs.endCycleL, this->end_cycle);
  write(mmio_addrs.endCycleH, this->end_cycle >> 32);
  write(mmio_addrs.doneInit, 1);
}

// Accepts the format string, and the masked arguments, and emits the formatted
// print to the desired stream
void synthesized_prints_t::print_format(const char *fmt,
                                        print_vars_t *vars,
                                        print_vars_t *masks) {
  size_t k = 0;
  if (print_cycle_prefix) {
    *printstream << "CYCLE:" << std::setw(13) << current_cycle << " ";
  }
  while (*fmt) {
    if (*fmt == '%' && fmt[1] != '%') {
      mpz_t *value = vars->data[k];
      char *v = nullptr;
      if (fmt[1] == 's' || fmt[1] == 'c') {
        size_t size;
        v = (char *)mpz_export(nullptr, &size, 1, sizeof(char), 0, 0, *value);
        for (size_t j = 0; j < size; j++)
          printstream->put(v[j]);
        fmt++;
        free(v);
      } else {
        char buf[1024];
        switch (*(++fmt)) {
        case 'h':
        case 'x':
          gmp_sprintf(
              buf, "%0*Zx", mpz_sizeinbase(*(masks->data[k]), 16), *value);
          break;
        case 'd':
          gmp_sprintf(
              buf, "%*Zd", mpz_sizeinbase(*(masks->data[k]), 10), *value);
          break;
        case 'b':
          mpz_get_str(buf, 2, *value);
          break;
        default:
          assert(0);
          break;
        }
        (*printstream) << buf;
      }
      fmt++;
      k++;
    } else if (*fmt == '%') {
      printstream->put(*(++fmt));
      fmt++;
    } else if (*fmt == '\\' && fmt[1] == 'n') {
      printstream->put('\n');
      fmt += 2;
    } else {
      printstream->put(*fmt);
      fmt++;
    }
  }
  assert(k == vars->data.size());
}

// Returns true if at least one print in the token is enabled in this cycle
bool has_enabled_print(char *buf) { return (buf[0] & 1); }
// If the token has no enabled prints, return a number of idle cycles encoded in
// the msbs
uint32_t decode_idle_cycles(char *buf, uint32_t mask) {
  return (((*((uint32_t *)buf)) & mask) >> 1);
}

/**
 * @brief Processes tokens at the head of a print bridge stream.
 *
 * @param beats The desired number of beats.
 * @param minimum_batch_beats The minimum number of beats to process on this
 *  invocation. Better amortizes stream bandwidth, set to 0 to drain the stream.
 *
 * @return * size_t The number of bytes processed.
 */
size_t synthesized_prints_t::process_tokens(size_t beats,
                                            size_t minimum_batch_beats) {
  size_t maximum_batch_bytes = beats * beat_bytes;
  size_t minimum_batch_bytes = minimum_batch_beats * beat_bytes;

  // See FireSim issue #208
  // This needs to be page aligned, as a DMA request that spans a page is
  // fractured into a pair, and for reasons unknown, first beat of the second
  // request is lost. Once aligned, requests larger than a page will be
  // fractured into page-size (64-beat) requests and these seem to behave
  // correctly.
  alignas(4096) char buf[maximum_batch_bytes];

  uint32_t bytes_received =
      pull(stream_idx, (char *)buf, maximum_batch_bytes, minimum_batch_bytes);

  if (human_readable) {
    for (size_t idx = 0; idx < bytes_received; idx += token_bytes) {
      if (has_enabled_print(&buf[idx])) {
        show_prints(&buf[idx]);
        current_cycle++;
      } else {
        current_cycle += decode_idle_cycles(&buf[idx], idle_cycles_mask);
      }
    }
  } else {
    printstream->write(buf, bytes_received);
  }

  return bytes_received;
}

// Returns true if the print at the current offset is enabled in this cycle
bool synthesized_prints_t::current_print_enabled(gmp_align_t *buf,
                                                 size_t offset) {
  return (buf[0] & (1LL << (offset)));
}

// Finds enabled prints in a token
void synthesized_prints_t::show_prints(char *buf) {
  for (size_t i = 0; i < print_count; i++) {
    gmp_align_t *data = ((gmp_align_t *)buf) + aligned_offsets[i];
    // First bit is enable
    if (current_print_enabled(data, bit_offset[i])) {
      mpz_t print;
      mpz_init(print);
      mpz_import(print, sizes[i], -1, sizeof(gmp_align_t), 0, 0, data);
      mpz_fdiv_q_2exp(print, print, bit_offset[i] + 1);

      print_vars_t vars;
      size_t num_args = argument_counts[i];
      for (size_t arg = 0; arg < num_args; arg++) {
        mpz_t *var = (mpz_t *)malloc(sizeof(mpz_t));
        mpz_t *mask = masks[i]->data[arg];
        mpz_init(*var);
        // *var = print & *mask
        mpz_and(*var, print, *mask);
        vars.data.push_back(var);
        // print = print >> width
        mpz_fdiv_q_2exp(print, print, widths[i][arg]);
      }
      print_format(format_strings[i], &vars, masks[i]);
      mpz_clear(print);
    }
  }
}

void synthesized_prints_t::tick() {
  // Pull batch_tokens from the FPGA if at least that many are avaiable
  // Assumes 1:1 token to dma-beat size
  process_tokens(batch_beats, batch_beats);
}

/**
 * @brief Drains all available tokens on the print bridge stream
 */
void synthesized_prints_t::flush() {
  // This should not starve the rest of the simulator because eventually some
  // other bridge in the system will need to be served and the stream will
  // empty. It might be safer to put a bound on this though.
  while (process_tokens(batch_beats, 0) != 0)
    ;
  pull_flush(stream_idx);
  process_tokens(batch_beats, 0);

  // If multiple tokens are being packed into a single stream beat, force the
  // widget to write out any incomplete beat
  if (token_bytes < beat_bytes) {
    write(mmio_addrs.flushNarrowPacket, 1);
    pull_flush(stream_idx);

    // On an FPGA reading from the stream will have enough latency that
    // process_tokens will return non-zero on the first attempt, introducing no
    // extra MMIO to wait for the flush to finish.
    // However, in metasimulation the delay model for MMIO can be very short so
    // repeatedly attempt to read from the stream until the narrow packet has
    // been returned.
    int max_attempts = (beat_bytes + token_bytes - 1) / token_bytes;
    int attempts = 0;
    while (process_tokens(batch_beats, 0) == 0) {
      if (attempts >= max_attempts) {
        std::cerr << "Printf narrow packet not flushed after " << max_attempts
                  << " polls of stream." << std::endl;
        exit(1);
      }
      attempts++;
    }
  }
  this->printstream->flush();
}
