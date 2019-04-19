#ifndef RTLSIM
#include "simif_f1.h"
#else
#include "simif_emul.h"
#endif
#include "firesim_top.h"

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
    firesim_f1_t firesim(argc, argv);
    firesim.init(argc, argv);
    firesim.run();
    return firesim.finish();
}
