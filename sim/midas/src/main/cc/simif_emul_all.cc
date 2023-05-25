#include <signal.h>
#include "emul/simif_emul.h"

/**
 * VCS-specific metasimulator implementation.
 */
class simif_emul_vcs_t final : public simif_emul_t {
public:
  simif_emul_vcs_t(const TargetConfig &config, int argc, char **argv);

  ~simif_emul_vcs_t() {}

  int run(simulation_t &sim);

private:
  int argc;
  char **argv;
};

simif_emul_vcs_t::simif_emul_vcs_t(const TargetConfig &config,
                                   int argc,
                                   char **argv)
    : simif_emul_t(config, std::vector<std::string>(argv + 1, argv + argc)),
      argc(argc), argv(argv) {
}

int simif_emul_vcs_t::run(simulation_t &sim) {
  start_driver(sim);
  return 0;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  return std::make_unique<simif_emul_vcs_t>(config, argc, argv);
}
