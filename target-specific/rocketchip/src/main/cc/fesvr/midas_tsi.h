// See LICENSE for license details.

#ifndef __MIDAS_TSI_H
#define __MIDAS_TSI_H

#include "midas_context.h"
#include "midas_fesvr.h"
#include "fesvr_proxy.h"

class midas_tsi_t : public fesvr_proxy_t, public midas_fesvr_t
{
 public:
  midas_tsi_t(const std::vector<std::string>& args);
  virtual ~midas_tsi_t();
  virtual void tick();

  virtual bool data_available();
  virtual void send_word(uint32_t word);
  virtual uint32_t recv_word();

  virtual bool recv_loadmem_req(fesvr_loadmem_t& loadmem);
  virtual void recv_loadmem_data(void* buf, size_t len);

  virtual bool busy() {
    return midas_fesvr_t::busy();
  }
  virtual bool done() {
    return midas_fesvr_t::done();
  }
  virtual int exit_code() {
    return midas_fesvr_t::exit_code();
  }

 private:
  midas_context_t host;
  midas_context_t* target;

  virtual void wait();
  static int host_thread(void *tsi);
};

#endif // __MIDAS_TSI_H
