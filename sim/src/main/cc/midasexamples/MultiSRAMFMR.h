#include "MultiRegfileFMR.h"

class MultiSRAMFMR_t : public MultiRegfileFMR_t {
public:
  MultiSRAMFMR_t(const std::vector<std::string> &args, simif_t *simif)
      : MultiRegfileFMR_t(args, simif) {}
};
