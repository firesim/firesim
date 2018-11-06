#include "synthesized_assertions.h"
#include <iostream>
#include <fstream>

void synthesized_assertions_t::tick() {
#ifdef ASSERTIONWIDGET_0
  if (read(ASSERTIONWIDGET_0(fire))) {
    // Read assertion information
    std::vector<std::string> msgs;
    std::ifstream file(std::string(TARGET_NAME) + ".asserts");
    std::string line;
    std::ostringstream oss;
    while (std::getline(file, line)) {
      if (line == "0") {
        msgs.push_back(oss.str());
        oss.str(std::string());
      } else {
        oss << line << std::endl;
      }
    }
    assert_cycle = read(ASSERTIONWIDGET_0(cycle_low));
    assert_cycle |= ((uint64_t)read(ASSERTIONWIDGET_0(cycle_high))) << 32;
    assert_id = read(ASSERTIONWIDGET_0(id));
    std::cerr << msgs[assert_id];
    std::cerr << " at cycle: " << assert_cycle << std::endl;
    assert_fired = true;
  }
#endif
}

void synthesized_assertions_t::resume() {
#ifdef ASSERTIONWIDGET_0
  assert_fired = false;
  write(ASSERTIONWIDGET_0(resume), 1);
#endif
}
