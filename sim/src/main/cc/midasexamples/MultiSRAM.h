#include "MultiRegfile.h"

class MultiSRAM_t : public MultiRegfile_t {
public:
  MultiSRAM_t(const std::vector<std::string> &args, simif_t *simif)
      : MultiRegfile_t(args, simif) {
    write_first = false;
  }
};
