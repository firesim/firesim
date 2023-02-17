// See LICENSE for license details

#include "dromajo.h"

#include <assert.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// Create bitmask macro
#define BIT_MASK(__TYPE__, __ONE_COUNT__)                                      \
  ((__TYPE__)(-((__ONE_COUNT__) != 0))) &                                      \
      (((__TYPE__)-1) >> ((sizeof(__TYPE__) * CHAR_BIT) - (__ONE_COUNT__)))

// #define DEBUG

char dromajo_t::KIND;

/**
 * Constructor for Dromajo
 */
dromajo_t::dromajo_t(simif_t &sim,
                     StreamEngine &stream,
                     std::vector<std::string> &args,
                     const DROMAJOBRIDGEMODULE_struct &mmio_addrs,
                     const dromajo_config_t &config,
                     int iaddr_width,
                     int insn_width,
                     int wdata_width,
                     int cause_width,
                     int tval_width,
                     int num_traces,
                     int stream_idx,
                     int stream_depth)
    : streaming_bridge_driver_t(sim, stream, &KIND), mmio_addrs(mmio_addrs),
      config(config), stream_idx(stream_idx), stream_depth(stream_depth) {
  // setup max constants given from the RTL
  this->_num_traces = num_traces;

  this->_valid_width = 1;
  this->_iaddr_width = iaddr_width / 8;
  this->_insn_width = insn_width / 8;
  this->_wdata_width = wdata_width / 8;
  this->_priv_width = 1;
  this->_exception_width = 1;
  this->_interrupt_width = 1;
  this->_cause_width = cause_width / 8;
  this->_tval_width = tval_width / 8;
  this->_valid_offset = 0;
  this->_iaddr_offset = this->_valid_offset + this->_valid_width;
  ;
  this->_insn_offset = this->_iaddr_offset + this->_iaddr_width;
  this->_wdata_offset = this->_insn_offset + this->_insn_width;
  this->_priv_offset = this->_wdata_offset + this->_wdata_width;
  this->_exception_offset = this->_priv_offset + this->_priv_width;
  this->_interrupt_offset = this->_exception_offset + this->_exception_width;
  this->_cause_offset = this->_interrupt_offset + this->_interrupt_width;
  this->_tval_offset = this->_cause_offset + this->_cause_width;

  // setup misc. state variables
  this->_trace_idx = 0;
  this->dromajo_failed = false;
  this->dromajo_exit_code = 0;
  this->dromajo_state = NULL;
  this->dromajo_cosim = true; // by default enable cosim
  this->saw_int_excp = false; // used to not trigger interrupts multiple times

  // setup dromajo variables
  std::string dromajo_dtb_arg = std::string("+drj_dtb=");
  std::string dromajo_bin_arg = std::string("+drj_bin=");
  std::string dromajo_rom_arg = std::string("+drj_rom=");
  bool dtb_found = false;
  bool bin_found = false;
  bool bootrom_found = false;
  this->dromajo_dtb = "";
  this->dromajo_bootrom = "";
  this->dromajo_bin = "";
  const char *d_rom = NULL;
  const char *d_dtb = NULL;
  const char *d_bin = NULL;

  for (auto &arg : args) {
    if (arg.find(dromajo_dtb_arg) == 0) {
      d_dtb = const_cast<char *>(arg.c_str()) + dromajo_dtb_arg.length();
      this->dromajo_dtb = std::string(d_dtb);
      dtb_found = true;
    }
    if (arg.find(dromajo_rom_arg) == 0) {
      d_rom = const_cast<char *>(arg.c_str()) + dromajo_rom_arg.length();
      this->dromajo_bootrom = std::string(d_rom);
      bootrom_found = true;
    }
    if (arg.find(dromajo_bin_arg) == 0) {
      d_bin = const_cast<char *>(arg.c_str()) + dromajo_bin_arg.length();
      this->dromajo_bin = std::string(d_bin);
      bin_found = true;
    }
  }

  if (!dtb_found || !bin_found || !bootrom_found) {
    printf("[WARNING] Missing Dromajo input file(s) (make sure you have a dtb, "
           "bin, and bootrom passed in)\n");
    printf("[WARNING] Disabling Dromajo Bridge\n");
    this->dromajo_cosim = false;
  }
}

/**
 * Destructor for Dromajo
 */
dromajo_t::~dromajo_t() {
  if (this->dromajo_state != NULL)
    dromajo_cosim_fini(this->dromajo_state);
}

/**
 * Setup simulation and initialize Dromajo cosimulation
 */
void dromajo_t::init() {
  // skip if co-sim not enabled
  if (!this->dromajo_cosim)
    return;

  printf("[INFO] Dromajo: Attached Dromajo to %d instruction traces\n",
         this->_num_traces);

  // setup arguments
  std::vector<std::string> dromajo_args{
      "./dromajo",
      "--compact_bootrom",
      "--custom_extension",
      "--clear_ids",
      "--reset_vector",
      config.resetVector,
      "--bootrom",
      dromajo_bootrom,
      "--mmio_range",
      std::string(config.mmioStart) + ":" + config.mmioEnd,
      "--plic",
      std::string(config.plicBase) + ":" + config.plicSize,
      "--clint",
      std::string(config.clintBase) + ":" + config.clintSize,
      "--memory_size",
      config.memSize,
      "--save",
      "dromajo_snap",
      "--dtb",
      dromajo_dtb,
      dromajo_bin};

  printf("[INFO] Dromajo command: \n");
  char *dromajo_argv[dromajo_args.size()];
  for (int i = 0; i < dromajo_args.size(); ++i) {
    dromajo_argv[i] = const_cast<char *>(dromajo_args[i].c_str());
    printf("%s ", dromajo_argv[i]);
  }
  printf("\n");

  this->dromajo_state = dromajo_cosim_init(dromajo_args.size(), dromajo_argv);
  if (this->dromajo_state == NULL) {
    printf("[ERROR] Error setting up Dromajo\n");
    exit(1);
  }
}

/**
 * Call Dromajo co-sim functions with an aligned buffer.
 * This returns the return code of the co-sim functions.
 */
int dromajo_t::invoke_dromajo(uint8_t *buf) {
  bool valid = buf[0];
  int hartid = 0; // only works for single core
  // this crazy to extract the right value then sign extend within the size
  uint64_t pc = ((int64_t)(*((int64_t *)(buf + this->_iaddr_offset)) &
                           BIT_MASK(uint64_t, this->_iaddr_width * 8))
                 << (sizeof(uint64_t) - this->_iaddr_width) * 8) >>
                (sizeof(uint64_t) - this->_iaddr_width) * 8;
  uint32_t insn = ((int32_t)(*((int32_t *)(buf + this->_insn_offset)) &
                             BIT_MASK(uint32_t, this->_insn_width * 8))
                   << (sizeof(uint32_t) - this->_insn_width) * 8) >>
                  (sizeof(uint32_t) - this->_insn_width) * 8;
  uint64_t wdata = ((int64_t)(*((int64_t *)(buf + this->_wdata_offset)) &
                              BIT_MASK(uint64_t, this->_wdata_width * 8))
                    << (sizeof(uint64_t) - this->_wdata_width) * 8) >>
                   (sizeof(uint64_t) - this->_wdata_width) * 8;
  uint64_t mstatus = 0; // default not checked
  bool check = true;    // default check all
  bool interrupt = buf[this->_interrupt_offset];
  bool exception = buf[this->_exception_offset];
  int64_t cause = (*((uint64_t *)(buf + this->_cause_offset)) &
                   BIT_MASK(uint64_t, this->_cause_width * 8)) |
                  ((uint64_t)interrupt << 63);

  if (valid) {
#ifdef DEBUG
    if (interrupt || exception)
      fprintf(
          stderr, "[DEBUG] INT/EXCEP raised (on valid): cause = %lx\n", cause);
#endif
    return dromajo_cosim_step(
        this->dromajo_state, hartid, pc, insn, wdata, mstatus, check);
  }

  if ((interrupt || exception) && !(this->saw_int_excp)) {
#ifdef DEBUG
    fprintf(stderr, "[DEBUG] INT/EXCEP raised (normal): cause = %lx\n", cause);
#endif
    dromajo_cosim_raise_trap(this->dromajo_state, hartid, cause);
    this->saw_int_excp = true;
  }

  return 0;
}

/**
 * Read queue and co-simulate
 */
size_t dromajo_t::process_tokens(int num_beats, size_t minimum_batch_beats) {
  size_t maximum_batch_bytes = num_beats * STREAM_WIDTH_BYTES;
  size_t minimum_batch_bytes = minimum_batch_beats * STREAM_WIDTH_BYTES;
  // TODO: as opt can mmap file and just load directly into it.
  alignas(4096) uint8_t OUTBUF[maximum_batch_bytes];
  auto bytes_received =
      pull(stream_idx, OUTBUF, maximum_batch_bytes, minimum_batch_bytes);

  // skip if co-sim not enabled
  if (!this->dromajo_cosim)
    return bytes_received;

  for (uint32_t offset = 0; offset < bytes_received;
       offset += STREAM_WIDTH_BYTES / 2) {
    // invoke dromajo (requires that buffer is aligned properly)
    int rval = this->invoke_dromajo(OUTBUF + offset);
    if (rval) {
      dromajo_failed = true;
      dromajo_exit_code = rval;
      printf("[ERROR] Dromajo: Errored during simulation with %d\n", rval);

#ifdef DEBUG
      fprintf(stderr,
              "C[%d] off(%d) token(",
              this->_trace_idx,
              offset / (STREAM_WIDTH_BYTES / 2));

      for (int32_t i = STREAM_WIDTH_BYTES - 1; i >= 0; --i) {
        fprintf(stderr, "%02x", (OUTBUF + offset)[i]);
        if (i == STREAM_WIDTH_BYTES / 2)
          fprintf(stderr, " ");
      }
      fprintf(stderr, ")\n");

      fprintf(stderr, "get_next_token token(");
      uint32_t next_off = offset += STREAM_WIDTH_BYTES;

      for (int32_t i = STREAM_WIDTH_BYTES - 1; i >= 0; --i) {
        fprintf(stderr, "%02x", (OUTBUF + next_off)[i]);
        if (i == STREAM_WIDTH_BYTES / 2)
          fprintf(stderr, " ");
      }
      fprintf(stderr, ")\n");
#endif

      return bytes_received;
    }

    // move to next inst. trace
    this->_trace_idx = (this->_trace_idx + 1) % this->_num_traces;

    // if int/excp was found in this set of commit traces... reset on next set
    // of commit traces
    if (this->_trace_idx == 0) {
      this->saw_int_excp = false;
    }

    // add an extra STREAM_WIDTH_BYTES if there is an odd amount of traces
    if (this->_trace_idx == 0 && (this->_num_traces % 2 == 1)) {
#ifdef DEBUG
      fprintf(stderr,
              "off(%d + 1) = %d\n",
              offset / (STREAM_WIDTH_BYTES / 2),
              (offset + STREAM_WIDTH_BYTES / 2) / (STREAM_WIDTH_BYTES / 2));
#endif
      offset += STREAM_WIDTH_BYTES / 2;
    }
  }

  return bytes_received;
}

/**
 * Move forward the simulation
 */
void dromajo_t::tick() {
  this->process_tokens(this->stream_depth, this->stream_depth);
}

/**
 * Pull in any remaining tokens and use them (if the simulation hasn't already
 * failed)
 */
void dromajo_t::flush() {
  // only flush if there wasn't a failure before
  while (!dromajo_failed && (this->process_tokens(this->stream_depth, 0) > 0))
    ;
}
