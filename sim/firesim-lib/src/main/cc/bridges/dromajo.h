// See LICENSE for license details
#ifndef __DROMAJO_H
#define __DROMAJO_H

#include "core/bridge_driver.h"
#include "dromajo_cosim.h"
#include <string>
#include <vector>

struct DROMAJOBRIDGEMODULE_struct {};

struct dromajo_config_t {
  const char *resetVector;
  const char *mmioStart;
  const char *mmioEnd;
  const char *memSize;
  const char *plicBase;
  const char *plicSize;
  const char *clintBase;
  const char *clintSize;
};

class dromajo_t : public streaming_bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  dromajo_t(simif_t &sim,
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
            int stream_depth);
  ~dromajo_t();

  virtual void init();
  virtual void tick();
  virtual bool terminate() { return dromajo_failed; };
  virtual int exit_code() { return (dromajo_failed) ? dromajo_exit_code : 0; };
  virtual void finish() { this->flush(); };

private:
  const DROMAJOBRIDGEMODULE_struct mmio_addrs;
  const dromajo_config_t config;
  simif_t *_sim;

  int invoke_dromajo(uint8_t *buf);
  int beats_available_stable();
  size_t process_tokens(int num_beats, size_t minimum_batch_beats);
  void flush();

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

  // other misc members
  uint32_t _num_traces;
  uint8_t _trace_idx;
  bool dromajo_failed;
  int dromajo_exit_code;
  bool dromajo_cosim;
  bool saw_int_excp;

  // dromajo specific
  std::string dromajo_dtb;
  std::string dromajo_bootrom;
  std::string dromajo_bin;
  dromajo_cosim_state_t *dromajo_state;

  // stream config
  int stream_idx;
  int stream_depth;
};

#endif // __DROMAJO_H
