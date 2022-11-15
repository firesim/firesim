#ifndef __RESET_PULSE_H
#define __RESET_PULSE_H

#ifdef RESETPULSEBRIDGEMODULE_struct_guard

#include <vector>

#include "bridge_driver.h"

// Bridge Driver Instantiation Template
#define INSTANTIATE_RESET_PULSE(FUNC, IDX)                                     \
  RESETPULSEBRIDGEMODULE_##IDX##_substruct_create;                             \
  FUNC(new reset_pulse_t(this,                                                 \
                         args,                                                 \
                         RESETPULSEBRIDGEMODULE_##IDX##_substruct,             \
                         RESETPULSEBRIDGEMODULE_##IDX##_max_pulse_length,      \
                         RESETPULSEBRIDGEMODULE_##IDX##_default_pulse_length,  \
                         IDX));

class reset_pulse_t : public bridge_driver_t {

public:
  reset_pulse_t(simif_t *sim,
                std::vector<std::string> &args,
                RESETPULSEBRIDGEMODULE_struct *mmio_addrs,
                unsigned int max_pulse_length,
                unsigned int default_pulse_length,
                int reset_index);
  // Bridge interface
  virtual void init();
  virtual void tick(){};
  virtual bool terminate() { return false; };
  virtual int exit_code() { return 0; };
  virtual void finish(){};

private:
  RESETPULSEBRIDGEMODULE_struct *mmio_addrs;
  const unsigned int max_pulse_length;
  const unsigned int default_pulse_length;

  unsigned int pulse_length = default_pulse_length;
};

#endif // RESETPULSEBRIDGEMODULE_struct_guard

#endif //__RESET_PULSE_H
