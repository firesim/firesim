#ifndef __REPLAY_VPI_H
#define __REPLAY_VPI_H

#include "vpi_user.h"
#include "replay.h"
#include "context.h"
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

  context_t *host;
  context_t target;

  void put_value(vpiHandle& sig, std::string& value, bool force=false);
  void get_value(vpiHandle& sig, std::string& value);
  virtual void put_value(vpiHandle& sig, biguint_t& data, bool force=false);
  virtual biguint_t get_value(vpiHandle& sig);
  virtual void take_steps(size_t n);

  inline void add_signal(vpiHandle& sig_handle, std::string& wire) {
    size_t id = replay_data.signals.size();
    replay_data.signals.push_back(sig_handle);
    replay_data.signal_map[wire] = id;
  }
};

#endif // __REPLAY_VPI_H
