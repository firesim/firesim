//See LICENSE for license details
#ifdef GROUNDTESTBRIDGEMODULE_struct_guard

#include "groundtest.h"

groundtest_t::groundtest_t(
        simif_t *sim, const std::vector<std::string> &args,
        GROUNDTESTBRIDGEMODULE_struct *mmio_addrs) :
    bridge_driver_t(sim), sim(sim), mmio_addrs(mmio_addrs)
{

}

groundtest_t::~groundtest_t()
{
    free(this->mmio_addrs);
}

void groundtest_t::init()
{
}

void groundtest_t::tick()
{
    _success = read(this->mmio_addrs->success);
}

#endif
