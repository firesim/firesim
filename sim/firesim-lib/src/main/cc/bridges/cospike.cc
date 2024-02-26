// See LICENSE for license details

#include "cospike.h"
#include "cospike_impl.h"

#include <assert.h>
#include <iostream>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// Create bitmask macro
#define BIT_MASK(__ITYPE__, __ONE_COUNT__)                                     \
  (((__ITYPE__)(-((__ONE_COUNT__) != 0))) &                                    \
   (((__ITYPE__)-1) >> ((sizeof(__ITYPE__) * CHAR_BIT) - (__ONE_COUNT__))))
#define TO_BYTES(__BITS__) ((__BITS__) / 8)

#define DEBUG

char cospike_t::KIND;

/**
 * Constructor for cospike
 */
cospike_t::cospike_t(simif_t &sim,
                     StreamEngine &stream,
                     int cospikeno,
                     std::vector<std::string> &args,
                     uint32_t iaddr_width,
                     uint32_t insn_width,
                     uint32_t cause_width,
                     uint32_t wdata_width,
                     uint32_t num_commit_insts,
                     uint32_t bits_per_trace,
                     const char *isa,
                     uint32_t vlen,
                     const char *priv,
                     uint32_t pmp_regions,
                     uint64_t mem0_base,
                     uint64_t mem0_size,
                     uint64_t mem1_base,
                     uint64_t mem1_size,
                     uint32_t nharts,
                     const char *bootrom,
                     uint32_t hartid,
                     uint32_t stream_idx,
                     uint32_t stream_depth)
    : streaming_bridge_driver_t(sim, stream, &KIND), args(args), _isa(isa),
      _vlen(vlen), _priv(priv), _pmp_regions(pmp_regions),
      _mem0_base(mem0_base), _mem0_size(mem0_size), _mem1_base(mem1_base),
      _mem1_size(mem1_size), _nharts(nharts), _bootrom(bootrom),
      _hartid(hartid), _num_commit_insts(num_commit_insts),
      _bits_per_trace(bits_per_trace), stream_idx(stream_idx),
      stream_depth(stream_depth) {
  this->_valid_width = 1;
  this->_iaddr_width = TO_BYTES(iaddr_width);
  this->_insn_width = TO_BYTES(insn_width);
  this->_exception_width = 1;
  this->_interrupt_width = 1;
  this->_cause_width = TO_BYTES(cause_width);
  this->_wdata_width = TO_BYTES(wdata_width);
  this->_priv_width = 1;

  // must align with how the trace is composed
  this->_valid_offset = 0;
  this->_iaddr_offset = this->_valid_offset + this->_valid_width;
  this->_insn_offset = this->_iaddr_offset + this->_iaddr_width;
  this->_priv_offset = this->_insn_offset + this->_insn_width;
  this->_exception_offset = this->_priv_offset + this->_priv_width;
  this->_interrupt_offset = this->_exception_offset + this->_exception_width;
  this->_cause_offset = this->_interrupt_offset + this->_interrupt_width;
  this->_wdata_offset = this->_cause_offset + this->_cause_width;

  this->cospike_failed = false;
  this->cospike_exit_code = 0;
}

/**
 * Setup simulation and initialize cospike cosimulation
 */
void cospike_t::init() {
  printf("[INFO] Cospike: Attached cospike to a single instruction trace with "
         "%d instructions.\n",
         this->_num_commit_insts);

  cospike_set_sysinfo((char *)this->_isa,
                      this->_vlen,
                      (char *)this->_priv,
                      this->_pmp_regions,
                      this->_mem0_base,
                      this->_mem0_size,
                      this->_mem1_base,
                      this->_mem1_size,
                      this->_nharts,
                      (char *)this->_bootrom,
                      this->args);
}

#define SHIFT_BITS(__RTYPE__, __BYTE_WIDTH__)                                  \
  ((sizeof(__RTYPE__) - (__BYTE_WIDTH__)) * 8)
#define SIGNED_EXTRACT_NON_ALIGNED(                                            \
    __ITYPE__, __RTYPE__, __BUF__, __BYTE_WIDTH__, __BYTE_OFFSET__)            \
  (*((__ITYPE__ *)((__BUF__) + (__BYTE_OFFSET__))) &                           \
   BIT_MASK(__RTYPE__, (__BYTE_WIDTH__)*8))
#define EXTRACT_ALIGNED(                                                       \
    __ITYPE__, __RTYPE__, __BUF__, __BYTE_WIDTH__, __BYTE_OFFSET__)            \
  ((((__ITYPE__)SIGNED_EXTRACT_NON_ALIGNED(                                    \
        __ITYPE__, __RTYPE__, __BUF__, __BYTE_WIDTH__, __BYTE_OFFSET__))       \
    << SHIFT_BITS(__RTYPE__, __BYTE_WIDTH__)) >>                               \
   SHIFT_BITS(__RTYPE__, __BYTE_WIDTH__))

/**
 * Call cospike co-sim functions with an aligned buffer.
 * This returns the return code of the co-sim functions.
 */
int cospike_t::invoke_cospike(uint8_t *buf) {
  bool valid = buf[0];
  // this crazy to extract the right value then sign extend within the size
  uint64_t iaddr = EXTRACT_ALIGNED(int64_t,
                                   uint64_t,
                                   buf,
                                   this->_iaddr_width,
                                   this->_iaddr_offset); // aka the pc
  uint32_t insn = EXTRACT_ALIGNED(
      int32_t, uint32_t, buf, this->_insn_width, this->_insn_offset);
  bool exception = buf[this->_exception_offset];
  bool interrupt = buf[this->_interrupt_offset];
  uint64_t cause = EXTRACT_ALIGNED(
      int64_t, uint64_t, buf, this->_cause_width, this->_cause_offset);
  uint64_t wdata =
      this->_wdata_width != 0
          ? EXTRACT_ALIGNED(
                int64_t, uint64_t, buf, this->_wdata_width, this->_wdata_offset)
          : 0;
  uint8_t priv = buf[this->_priv_offset];

#ifdef DEBUG
  fprintf(stderr,
          "C[%d] V(%d) PC(0x%lx) Insn(0x%x) EIC(%d:%d:%ld) Wdata(%d:0x%lx) "
          "Priv(%d)\n",
          this->_hartid,
          valid,
          iaddr,
          insn,
          exception,
          interrupt,
          cause,
          (this->_wdata_width != 0),
          wdata,
          priv);
#endif

  if (valid || exception || cause) {
    return cospike_cosim(0, // TODO: No cycle given
                         this->_hartid,
                         (this->_wdata_width != 0),
                         valid,
                         iaddr,
                         insn,
                         exception,
                         interrupt,
                         cause,
                         wdata,
                         priv);
  } else {
    return 0;
  }
}

/**
 * Read queue and co-simulate
 */
size_t cospike_t::process_tokens(int num_beats, size_t minimum_batch_beats) {
  const size_t maximum_batch_bytes = num_beats * STREAM_WIDTH_BYTES;
  const size_t minimum_batch_bytes = minimum_batch_beats * STREAM_WIDTH_BYTES;
  // TODO: as opt can mmap file and just load directly into it.
  page_aligned_sized_array(OUTBUF, maximum_batch_bytes);
  auto bytes_received =
      pull(stream_idx, OUTBUF, maximum_batch_bytes, minimum_batch_bytes);
  const size_t bytes_per_trace = this->_bits_per_trace / 8;

  for (uint32_t offset = 0; offset < bytes_received;
       offset += bytes_per_trace) {
#ifdef DEBUG
    fprintf(stderr,
            "Off(%d/%ld:%lu) token(",
            offset,
            bytes_received,
            offset / bytes_per_trace);

    for (int32_t i = STREAM_WIDTH_BYTES - 1; i >= 0; --i) {
      fprintf(stderr, "%02x", (OUTBUF + offset)[i]);
      if (i == bytes_per_trace)
        fprintf(stderr, " ");
    }
    fprintf(stderr, ")\n");
#endif

    // invoke cospike (requires that buffer is aligned properly)
    int rval = this->invoke_cospike(((uint8_t *)OUTBUF) + offset);
    if (rval) {
      cospike_failed = true;
      cospike_exit_code = rval;
      printf("[ERROR] Cospike: Errored during simulation with %d\n", rval);

#ifdef DEBUG
      fprintf(stderr, "Off(%lu) token(", offset / bytes_per_trace);

      for (int32_t i = STREAM_WIDTH_BYTES - 1; i >= 0; --i) {
        fprintf(stderr, "%02x", (OUTBUF + offset)[i]);
        if (i == bytes_per_trace)
          fprintf(stderr, " ");
      }
      fprintf(stderr, ")\n");

      fprintf(stderr, "get_next_token token(");
      auto next_off = offset + STREAM_WIDTH_BYTES;

      for (auto i = STREAM_WIDTH_BYTES - 1; i >= 0; --i) {
        fprintf(stderr, "%02x", (OUTBUF + next_off)[i]);
        if (i == bytes_per_trace)
          fprintf(stderr, " ");
      }
      fprintf(stderr, ")\n");
#endif

      break;
    }
  }

  return bytes_received;
}

/**
 * Move forward the simulation
 */
void cospike_t::tick() {
  this->process_tokens(this->stream_depth, this->stream_depth);
}

/**
 * Pull in any remaining tokens and use them (if the simulation hasn't already
 * failed)
 */
void cospike_t::flush() {
  // only flush if there wasn't a failure before
  while (!cospike_failed && (this->process_tokens(this->stream_depth, 0) > 0))
    ;
}
