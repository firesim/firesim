
#include <signal.h>

#include "emul/simif_emul.h"

/**
 * VCS-specific metasimulator implementation.
 */
class simif_emul_vcs_t final : public simif_emul_t {
public:
  simif_emul_vcs_t(const TargetConfig &config,
                   const std::vector<std::string> &args)
      : simif_emul_t(config, args) {}

  ~simif_emul_vcs_t() {}
};

/// Simulator instance used by DPI.
simif_emul_t *simulator = nullptr;

extern "C" {
int vcs_main(int argc, char **argv);
}

/**
 * Entry point to the VCS meta-simulation hijacked from VCS itself.
 */
int main(int argc, char **argv) {
  std::vector<std::string> args{argv + 1, argv + argc};
  simulator = new simif_emul_vcs_t(conf_target, args);
  return vcs_main(argc, argv);
}
