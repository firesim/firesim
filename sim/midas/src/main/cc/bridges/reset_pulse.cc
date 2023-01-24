
#include "reset_pulse.h"

char reset_pulse_t::KIND;

reset_pulse_t::reset_pulse_t(simif_t &sim,
                             const std::vector<std::string> &args,
                             const RESETPULSEBRIDGEMODULE_struct &mmio_addrs,
                             unsigned int max_pulse_length,
                             unsigned int default_pulse_length,
                             int reset_index)
    : bridge_driver_t(sim, &KIND), mmio_addrs(mmio_addrs),
      max_pulse_length(max_pulse_length),
      default_pulse_length(default_pulse_length) {

  std::string num_equals = std::to_string(reset_index) + std::string("=");
  std::string pulse_length_arg =
      std::string("+reset-pulse-length") + num_equals;

  for (const auto &arg : args) {
    if (arg.find(pulse_length_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + pulse_length_arg.length();
      this->pulse_length = atol(str);
    }
  }
  if (this->pulse_length > this->max_pulse_length) {
    fprintf(
        stderr,
        "Requested reset length of %u exceeds bridge maximum of %u cycles.\n",
        this->pulse_length,
        this->max_pulse_length);
    abort();
  }
}

void reset_pulse_t::init() {
  write(mmio_addrs.pulseLength, this->pulse_length);
  write(mmio_addrs.doneInit, 1);
}
