#ifndef __PRINT_H
#define __PRINT_H

#include <gmp.h>
#include <array>

struct print_vars_t {
  std::vector<mpz_t*> data;
  ~print_vars_t() {
    for (auto& e: data) {
      mpz_clear(*e);
      free(e);
    }
  }
};

struct print_state_t {
  bool enable;
  char* prints;
  std::array<std::string,              PRINTS_NUM> formats;
  std::array<std::vector<std::string>, PRINTS_NUM> names;
  std::array<std::vector<size_t>,      PRINTS_NUM> widths;

  size_t sizes[PRINTS_NUM];
  print_vars_t* masks[PRINTS_NUM];

  print_state_t() {
    prints = new char[512 * 1024]();
    for (size_t i = 0 ; i < PRINTS_NUM ; i++) {
      masks[i] = new print_vars_t;
    }
  }
  ~print_state_t() {
    delete[] prints;
    for (size_t i = 0 ; i < PRINTS_NUM ; i++) {
      delete masks[i];
    }
  }
};

#endif // __PRINT_H
