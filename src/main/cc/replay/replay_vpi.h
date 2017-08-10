#ifndef __REPLAY_VPI_H
#define __REPLAY_VPI_H

#include "vpi_user.h"
#include "replay.h"
#include "midas_context.h"
#include <queue>

class replay_vpi_t: public replay_t<vpiHandle> {
public:
  replay_vpi_t() { }
  virtual ~replay_vpi_t() { }

  virtual void init(int argc, char** argv);
  virtual int finish();
  void probe_signals();
  void tick();

private:
  std::queue<vpiHandle> forces;

  midas_context_t *host;
  midas_context_t target;

  inline void add_signal(vpiHandle& sig_handle, std::string& path);
  inline void probe_bits(vpiHandle& sig_handle, std::string& sigpath, std::string& modname);
  void put_value(vpiHandle& sig, std::string& value, PLI_INT32 flag);
  void get_value(vpiHandle& sig, std::string& value);
  virtual void put_value(vpiHandle& sig, mpz_t& data, PUT_VALUE_TYPE type);
  virtual void get_value(vpiHandle& sig, mpz_t& data);
  virtual void take_steps(size_t n);
};

#endif // __REPLAY_VPI_H
