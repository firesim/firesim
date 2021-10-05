//See LICENSE for license details
#include "simif_vitis.h"
#include "firesim_top.h"
#include <exception>
#include <stdio.h>

// top for RTL sim
class firesim_vitis_t:
    public simif_vitis_t, public firesim_top_t
{
    public:
        firesim_vitis_t(int argc, char** argv): simif_vitis_t(argc, argv), firesim_top_t(argc, argv) {};
};

int main(int argc, char** argv) {
  try {
    firesim_vitis_t firesim(argc, argv);
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
