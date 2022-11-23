// See LICENSE for license details
#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "firesim_top.h"
#include <exception>
#include <stdio.h>

// top for RTL sim
class firesim_f1_t : public SIMIF, public firesim_top_t {
public:
  firesim_f1_t(const std::vector<std::string> &args)
      : SIMIF(args), firesim_top_t(args, this) {}
};

int main(int argc, char **argv) {
  try {
    std::vector<std::string> args(argv + 1, argv + argc);
    firesim_f1_t firesim(args);
    firesim.init(argc, argv);
    firesim.run();
    return firesim.teardown();
  } catch (std::exception &e) {
    fprintf(stderr,
            "Caught Exception leaving %s: %s.\n",
            __PRETTY_FUNCTION__,
            e.what());
    abort();
  } catch (...) {
    // seriously, VCS will give you an unhelpful message if you let an exception
    // propagate catch it here and if we hit this, I can go rememeber how to
    // unwind the stack to print a trace
    fprintf(
        stderr, "Caught non std::exception leaving %s\n", __PRETTY_FUNCTION__);
    abort();
  }
}
