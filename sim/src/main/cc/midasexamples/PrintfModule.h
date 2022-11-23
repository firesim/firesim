// See LICENSE for license details.

#include <memory>

#include "bridges/synthesized_prints.h"
#include "simif_peek_poke.h"

class print_module_t : public simif_peek_poke_t {
public:
  std::vector<synthesized_prints_t *> print_endpoints;
  print_module_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create){
#ifdef PRINTBRIDGEMODULE_0_PRESENT
            INSTANTIATE_PRINTF(print_endpoints.push_back, 0)
#endif
#ifdef PRINTBRIDGEMODULE_1_PRESENT
                INSTANTIATE_PRINTF(print_endpoints.push_back, 1)
#endif
        };
  void run_and_collect_prints(int cycles) {
    step(cycles, false);
    while (!simif->done()) {
      for (auto &print_endpoint : print_endpoints) {
        print_endpoint->tick();
      }
    }
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->finish();
    }
  };
};

#ifdef DESIGNNAME_PrintfModule
class PrintfModule_t : public print_module_t {
public:
  PrintfModule_t(const std::vector<std::string> &args, simif_t *simif)
      : print_module_t(args, simif) {}
  virtual void run() {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke(reset, 1);
    poke(io_a, 0);
    poke(io_b, 0);
    step(1);
    poke(reset, 0);
    step(1);
    poke(io_a, 1);
    poke(io_b, 1);
    run_and_collect_prints(16000);
  };
};
#endif // DESIGNNAME_PrintfModule

#ifdef DESIGNNAME_AutoCounterPrintfModule
class AutoCounterPrintfModule_t : public print_module_t {
public:
  AutoCounterPrintfModule_t(const std::vector<std::string> &args,
                            simif_t *simif)
      : print_module_t(args, simif) {}
  virtual void run() {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke(reset, 1);
    poke(io_a, 0);
    step(1);
    poke(reset, 0);
    step(1);
    poke(io_a, 1);
    run_and_collect_prints(16000);
  };
};
#endif // DESIGNNAME_AutoCounterPrintf

#ifdef DESIGNNAME_TriggerPredicatedPrintf
class TriggerPredicatedPrintf_t : public print_module_t {
public:
  TriggerPredicatedPrintf_t(const std::vector<std::string> &args,
                            simif_t *simif)
      : print_module_t(args, simif) {}
  virtual void run() {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    run_and_collect_prints(16000);
  };
};
#endif // DESIGNNAME_AutoCounterPrintf

#ifdef DESIGNNAME_PrintfGlobalResetCondition
class PrintfGlobalResetCondition_t : public print_module_t {
public:
  PrintfGlobalResetCondition_t(const std::vector<std::string> &args,
                               simif_t *simif)
      : print_module_t(args, simif) {}
  virtual void run() {
    poke(reset, 1);
    // To be safe, must be at least the length of the number of pipeine
    // registers on each reset * maximum clock division.
    step(4);
    poke(reset, 0);
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    run_and_collect_prints(1000);
  };
};
#endif // DESIGNNAME_PrintfGlobalResetCondition
