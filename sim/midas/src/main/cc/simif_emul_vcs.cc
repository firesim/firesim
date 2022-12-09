
#include <signal.h>

#include "emul/context.h"

#include "simif_emul_vcs.h"

simif_emul_vcs_t *emulator = nullptr;

static int saved_argc;
static char **saved_argv;

extern "C" {
extern int vcs_main(int argc, char **argv);
}

void target_thread(void *arg) { vcs_main(saved_argc, saved_argv); }

void handle_sigterm(int sig) {
  if (emulator) {
    emulator->finish();
  }
}

simif_emul_vcs_t::simif_emul_vcs_t(const std::vector<std::string> &args)
    : simif_emul_t(args) {
  assert(emulator == nullptr && "a single emulator instance is required");
  emulator = this;
}

void simif_emul_vcs_t::sim_init() {
  signal(SIGTERM, handle_sigterm);

  host = pcontext_t::current();
  target.init(target_thread, nullptr);
  vcs_rst = true;
  for (size_t i = 0; i < 10; i++)
    target.switch_to();
  vcs_rst = false;
}

void simif_emul_vcs_t::advance_target() {
  int cycles_to_wait = rand_next(maximum_host_delay) + 1;
  for (int i = 0; i < cycles_to_wait; i++) {
    target.switch_to();
  }
}

void simif_emul_vcs_t::finish() {
  vcs_fin = true;
  target.switch_to();
}

int main(int argc, char **argv) {
  saved_argc = argc;
  saved_argv = argv;
  std::vector<std::string> args(argv + 1, argv + argc);
  return simif_emul_vcs_t(args).run();
}
