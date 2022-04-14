#ifdef TESTFINISHERBRIDGEMODULE_struct_guard

#include "test_finisher.h"

test_finisher_t::test_finisher_t(simif_t* sim, TESTFINISHERBRIDGEMODULE_struct * mmio_addrs): bridge_driver_t(sim)
{
  this->mmio_addrs = mmio_addrs;
}

test_finisher_t::~test_finisher_t() {
	free(this->mmio_addrs);
}

void test_finisher_t::tick() {  //reads the MMIOs every tick
  if (read(this->mmio_addrs->out_status)) {
	  printf("test done");
	  test_done = true;
	}
}
#endif
