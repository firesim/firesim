#include "MultiRegfile.h"

class MultiSRAM_t: public MultiRegfile_t
{
 public:
 MultiSRAM_t(int argc, char** argv) : MultiRegfile_t(argc, argv) { write_first = false; }
};
