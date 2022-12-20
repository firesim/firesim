// See LICENSE for license details
#ifndef __DROMAJO_H
#define __DROMAJO_H

#include "bridges/bridge_driver.h"
#include "dromajo_cosim.h"
#include <string>
#include <vector>

#ifdef DROMAJOBRIDGEMODULE_0_PRESENT

typedef struct DROMAJOBRIDGEMODULE_struct {

} DROMAJOBRIDGEMODULE_struct;

class dromajo_t : public bridge_driver_t {
public:
  dromajo_t(simif_t *sim,
            std::vector<std::string> &args,
            int iaddr_width,
            int insn_width,
            int wdata_width,
            int cause_width,
            int tval_width,
            int num_traces,
            const DROMAJOBRIDGEMODULE_struct &mmio_addrs,
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
  long _dma_addr;
  const unsigned int stream_count_address;
  const unsigned int stream_full_address;
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
};
#endif // DROMAJOBRIDGEMODULE_0_PRESENT
#endif // __DROMAJO_H
