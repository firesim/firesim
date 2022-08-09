#include "../../tracerv_processing.h"

int main(int argc, char *argv[]) {
  ObjdumpedBinary bin(
      (argc > 1) ? argv[1]
                 : "../../../../../../sw/firesim-software/riscv-linux/vmlinux");
  return 0;
}
