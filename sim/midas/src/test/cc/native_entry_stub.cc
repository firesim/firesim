// See LICENSE for license details.
#include <cstdio>
#include <stdexcept>
#include <string>

namespace {
struct Cleanup {
  const char *name;
  ~Cleanup() { fprintf(stderr, "%s cleaned up\n", name); }
};
Cleanup global{"global"};
} // namespace

int entry(int argc, char **argv) {
  Cleanup local{"local"};
  if (argc != 2)
    throw std::invalid_argument("expected one case");
  const std::string name(argv[1]);
  if (name == "success")
    return 0;
  if (name == "failure-code")
    return 17;
  if (name == "standard")
    throw std::runtime_error("intentional entry failure");
  if (name == "nonstandard")
    throw 42;
  throw std::invalid_argument("unknown case");
}
