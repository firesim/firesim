// See LICENSE for license details.
// CPU-only: null backing with an explicit size exercises the actual buddy
// bookkeeping and UntypedBuffer constructors; no physical-address access.
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vrt/allocator/allocator.hpp>

static void check(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
template <size_t Min, size_t Max>
class BuddyTest : public vrt::BuddySuperblockBase<Min, Max> {
public:
  void seed() {
    vrt::UntypedBuffer whole(
        static_cast<vrtd::Buffer *>(nullptr), 1ULL << Max, 0);
    vrt::BuddySuperblockBase<Min, Max>::seed(whole, "small", "large");
  }
  vrt::UntypedBuffer allocate(uint64_t size) {
    return vrt::BuddySuperblockBase<Min, Max>::allocate(size, "small");
  }
  void exhausted(uint64_t size) {
    bool rejected = false;
    try {
      allocate(size);
    } catch (const std::bad_alloc &) {
      rejected = true;
    }
    check(rejected, "exhaustion returned a null slice instead of bad_alloc");
  }
};
int main(int argc, char **argv) {
  try {
    check(argc == 2, "one allocator scenario required");
    const std::string name(argv[1]);
    BuddyTest<21, 26> large;
    BuddyTest<12, 21> medium;
    if (name == "empty-large") {
      large.exhausted(1 << 21);
    } else if (name == "empty-medium") {
      medium.exhausted(1 << 12);
    } else if (name == "split-exhaustion") {
      large.seed();
      check(large.allocate(1 << 25).getSize() == (1 << 25), "first split size");
      check(large.allocate(1 << 25).getSize() == (1 << 25),
            "second split size");
      large.exhausted(1 << 21);
    } else if (name == "oversized-control") {
      large.exhausted(1ULL << 27);
    } else {
      throw std::runtime_error("unknown allocator scenario");
    }
    std::printf("%s: PASS (predicted bookkeeping, no hardware)\n",
                name.c_str());
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
