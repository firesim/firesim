
#include <signal.h>

#include "simif_emul_vcs.h"

simif_emul_vcs_t::simif_emul_vcs_t(const std::vector<std::string> &args)
    : simif_emul_t(args) {}

simif_emul_vcs_t::~simif_emul_vcs_t() {}

/// Simulator instance used by DPI.
simif_emul_vcs_t *simulator = nullptr;

extern "C" {
int vcs_main(int argc, char **argv);
}

/**
 * Entry point to the VCS meta-simulation hijacked from VCS itself.
 */
int main(int argc, char **argv) {
  std::vector<std::string> args{argv + 1, argv + argc};
  simulator = new simif_emul_vcs_t(args);
  return vcs_main(argc, argv);
}
