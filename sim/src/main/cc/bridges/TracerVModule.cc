// See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "bridges/tracerv.h"
#include <random>

#include "bridges/autocounter.h"
#include "bridges/bridge_driver.h"
#include "bridges/peek_poke.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

#include "TestHarness.h"
#include "BridgeHarness.h"

#include <iostream>

std::vector<bool> get_contiguous(const unsigned bits, const unsigned total) {
  std::vector<bool> ret;
  for(unsigned i = 0; i < total; i++) {
    const bool value = (i < bits);
    ret.emplace_back(value);
    // std::cout << value << "\n";
  }

  return ret;
}

std::vector<uint64_t> get_iaddrs(const unsigned step, const unsigned total) {
  constexpr uint64_t offset = 1024; // should be larger than total but doesn't really matter
  std::vector<uint64_t> ret;

  for(unsigned i = 0; i < total; i++) {
    ret.emplace_back(step*offset+i);
  }

  return ret;
}

class TracerVModule final : public simulation_t {
public:
  TracerVModule(const std::vector<std::string> &args, simif_t *simif)
      : simulation_t(*simif, args), simif(simif) {
    std::cout << "TracerVModule()" << std::endl;
  }

  virtual ~TracerVModule() {
    std::cout << "~TracerVModule()" << std::endl;
  }

  void add_bridge_driver(bridge_driver_t *bridge) {
    bridges.emplace_back(bridge);
  }

  void add_bridge_driver(peek_poke_t *bridge) {
    peek_poke.reset(bridge);
  }

  void add_bridge_driver(tracerv_t *bridge) {
    std::cout << "tracerv::add_bridge_driver(tracerv)\n";
    assert(!tracerv && "multiple bridges registered");
    tracerv.reset(bridge);
  }

  void simulation_init() override {
    std::cout << "simulation_init\n";
#include "constructor.h"
    for (auto &bridge : bridges) {
      std::cout << "init bridge\n";
      bridge->init();
    }
    if(tracerv) {
      tracerv->init();
    } 
  }

  // call init on tracerV
  // call tick 1:1 with take_steps
  // copy termination brige take_steps
  // call take_steps for 100
  // the in a loop, tick all bridges, untill done() is true
  //  

  // expose a vec of iaddr, and valid 
  //   consider ValidIO,, a bundle with valid implicit (valid/ready without the ready) just expose iaddr

  // add require to traverVBridge
  // consider PR a test before widht changes
  

  // take N steps
  // tick all of the bridges until done is asserted
  // there is a timeout to see if done never returns
  // returns false for error
  // returns true for success
  bool steps(const unsigned s) {
    simif->take_steps(s, /*blocking=*/false);  
    const unsigned timeout = 10000 + s;
    bool was_done = false;
    for(unsigned i = 0; i < timeout; i++) {

      for (auto &bridge : bridges) {
        bridge->tick();
      }
      tracerv->tick();


      if(simif->done()) {
        was_done = true;
        break;
      }
    }

    if(!was_done) {
      std::cout << "Hit timeout of " << timeout << " tick loops afer a requested " << s << " steps" << std::endl;
    }

    return was_done;
  }

  std::vector<std::vector<uint64_t>> expected_iaddr;
  std::vector<std::vector<bool>> expected_valid;
  
  int simulation_run() override {
    std::cout << "simulation_run" << std::endl;
    if(!tracerv) {
      std::cout << "tracerv was never set" << std::endl;
    }

    // for(unsigned i2 = 0; i2 < 300; i2++) {
    //   std::cout << simif->rand_next(32) << std::endl;
    // }

    // Reset the DUT.
    peek_poke->poke("reset", 1, /*blocking=*/true);
    simif->take_steps(1, /*blocking=*/true);
    peek_poke->poke("reset", 0, /*blocking=*/true);
    simif->take_steps(1, /*blocking=*/true);


    // peek_poke->poke("io_tracervdebug", 9999, /*blocking=*/true);

    // std::cout << "Step A" << std::endl;

    // steps(1);

    // // these two are a problem
    // simif->take_steps(100, /*blocking=*/false);

    // steps(100);

    // std::cout << "Step B" << std::endl;
    // peek_poke->poke("io_tracervdebug", 100, /*blocking=*/true);

    // steps(1);

    // std::cout << "Step C" << std::endl;

    // peek_poke->poke("io_tracervdebug", 100, /*blocking=*/true);

    // steps(10);

    auto namei = [](const unsigned x) {
      std::stringstream ss;
      ss << "io_insns_" << x << "_iaddr";
      return ss.str();
    };

    auto namev = [](const unsigned x) {
      std::stringstream ss;
      ss << "io_insns_" << x << "_valid";
      return ss.str();
    };

    

    const unsigned tracerv_width = 4;


    auto load = [&](std::vector<uint64_t> iad, std::vector<bool> btt) {
      assert(iad.size() == btt.size());
      const auto sz = iad.size();
      for(unsigned i = 0; i < sz; i++) {
        std::cout << "loading " << i << " with " << iad[i] << "," << btt[i] << std::endl;
        peek_poke->poke(namei(i), iad[i], true);
        peek_poke->poke(namev(i), btt[i], true);
      }
    };

    for(unsigned test_step = 0; test_step < 10; test_step++) {
      const uint64_t pull = simif->rand_next(tracerv_width);

      auto pull_iaddr = get_iaddrs(test_step, tracerv_width);
      auto pull_bits = get_contiguous(pull, tracerv_width);

      load(pull_iaddr, pull_bits);
      steps(1);
    }
    
    std::vector<bool> final_valid;
    std::vector<uint64_t> final_iaddr;

    for(unsigned i = 0; i < tracerv_width; i++) {
      final_valid.emplace_back(0);
      final_iaddr.emplace_back(0xffff);
    }

    load(final_iaddr, final_valid);
    steps(get_step_limit());




    // peek_poke->poke("io_insns_0_iaddr", 4, /*blocking=*/true);
    // peek_poke->poke("io_insns_0_valid", 1, /*blocking=*/true);
    // peek_poke->poke("io_insns_1_iaddr", 4+100, /*blocking=*/true);
    // peek_poke->poke("io_insns_1_valid", 1, /*blocking=*/true);
    // steps(1);
    // peek_poke->poke("io_insns_0_valid", 0, /*blocking=*/true);
    // peek_poke->poke("io_insns_1_valid", 0, /*blocking=*/true);


    // steps(get_step_limit());

    // Cleanup.
    return EXIT_SUCCESS;
  }
  void simulation_finish() override {
    for (auto &bridge : bridges) {
      bridge->finish();
    }
  }

private:
  simif_t *simif;
  std::vector<std::unique_ptr<bridge_driver_t>> bridges;
  std::unique_ptr<peek_poke_t> peek_poke;

  unsigned get_step_limit() const { return 6000; }
  unsigned get_tick_limit() const { return 3000; }

  std::unique_ptr<tracerv_t> tracerv;
};

TEST_MAIN(TracerVModule)
