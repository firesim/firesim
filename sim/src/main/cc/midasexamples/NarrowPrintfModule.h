// See LICENSE for license details.
#include <iostream>
#include "PrintfModule.h"
class NarrowPrintfModule_t : public print_module_t {
public:
  NarrowPrintfModule_t(int argc, char **argv) : print_module_t(argc, argv){};
  virtual void run() {
    token_hashers->set_params(0, 16);


    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke(reset, 1);
    poke(io_enable, 0);
    step(1);
    poke(io_enable, 1);
    poke(reset, 0);
    step(4);
    // Test idle-cycle rollover
    poke(io_enable, 0);
    step(256);
    poke(io_enable, 1);
    run_and_collect_prints(256);

    std::cout << token_hashers->get_csv_string();
    token_hashers->write_csv_file("rocket-f1-run.csv");

  };
};
