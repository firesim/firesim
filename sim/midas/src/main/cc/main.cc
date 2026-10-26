// See LICENSE for license details.

#include "entry.h"
#include <cstdio>
#include <cstdlib>
#include <exception>

// Calls the entry point of the driver.
int main(int argc, char **argv) {
  try {
    return entry(argc, argv);
  } catch (const std::exception &error) {
    fprintf(stderr, "Simulation driver failed: %s\n", error.what());
  } catch (...) {
    fprintf(stderr, "Simulation driver failed: unknown exception\n");
  }
  return EXIT_FAILURE;
}
