// See LICENSE for license details
#ifndef __COSPIKE_H
#define __COSPIKE_H

#include "core/bridge_driver.h"
#include <string>
#include <vector>

class cospike_t : public streaming_bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  cospike_t(simif_t &sim,
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
            uint32_t stream_depth);

  ~cospike_t() override = default;

  void init() override;
  void tick() override;
  bool terminate() override { return cospike_failed; };
  int exit_code() override { return (cospike_failed) ? cospike_exit_code : 0; };
  void finish() override { this->flush(); };

private:
  int invoke_cospike(uint8_t *buf);
  size_t process_tokens(int num_beats, size_t minimum_batch_beats);
  void flush();

  std::vector<std::string> args;

  // in bytes
  uint32_t _valid_width;
  uint32_t _iaddr_width;
  uint32_t _insn_width;
  uint32_t _wdata_width;
  uint32_t _priv_width;
  uint32_t _exception_width;
  uint32_t _interrupt_width;
  uint32_t _cause_width;
  uint32_t _tval_width;

  // in bytes
  uint32_t _valid_offset;
  uint32_t _iaddr_offset;
  uint32_t _insn_offset;
  uint32_t _wdata_offset;
  uint32_t _priv_offset;
  uint32_t _exception_offset;
  uint32_t _interrupt_offset;
  uint32_t _cause_offset;
  uint32_t _tval_offset;

  const char *_isa;
  uint32_t _vlen;
  const char *_priv;
  uint32_t _pmp_regions;
  uint64_t _mem0_base;
  uint64_t _mem0_size;
  uint64_t _mem1_base;
  uint64_t _mem1_size;
  uint32_t _nharts;
  const char *_bootrom;
  uint32_t _hartid;

  // other misc members
  uint32_t _num_commit_insts;
  uint32_t _bits_per_trace;
  bool cospike_failed;
  int cospike_exit_code;

  // stream config
  int stream_idx;
  int stream_depth;
};

#endif // __COSPIKE_H
