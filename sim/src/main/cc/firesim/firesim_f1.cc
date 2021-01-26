//See LICENSE for license details
#ifndef RTLSIM
#include "simif_f1.h"
#else
#include "simif_emul.h"
#endif
#include "firesim_top.h"
#include <exception>
#include <stdio.h>

// top for RTL sim
class firesim_f1_t:
#ifdef RTLSIM
    public simif_emul_t, public firesim_top_t
#else
    public simif_f1_t, public firesim_top_t
#endif
{
    public:
#ifdef RTLSIM
        firesim_f1_t(int argc, char** argv): firesim_top_t(argc, argv) {};
#else
        firesim_f1_t(int argc, char** argv): simif_f1_t(argc, argv), firesim_top_t(argc, argv) {};
#endif
};

int main(int argc, char** argv) {
  try {
    firesim_f1_t firesim(argc, argv);
    firesim.init(argc, argv);
    firesim.run();
    return firesim.teardown();
  }
  catch (std::exception& e) {
    fprintf(stderr, "Caught Exception leaving %s: %s.\n", __PRETTY_FUNCTION__, e.what());
    abort();
  }
  catch (...) {
    // seriously, VCS will give you an unhelpful message if you let an exception propagate
    // catch it here and if we hit this, I can go rememeber how to unwind the stack to print a trace
    fprintf(stderr, "Caught non std::exception leaving %s\n", __PRETTY_FUNCTION__);
    abort();
  }
}
