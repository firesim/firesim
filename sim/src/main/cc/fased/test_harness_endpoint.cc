//See LICENSE for license details.

#include "test_harness_endpoint.h"

void test_harness_endpoint_t::tick(){
    this->done = sim->peek(done);
}
