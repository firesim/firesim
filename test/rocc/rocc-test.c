// This test program uses the example ROCC accelerator that is included with
// spike by default (called dummy-rocc).
// Use the provided Makefile to build
// Run with spike --extension=dummy_rocc rocc-test.riscv

#include <stdio.h>
#include <stdint.h>
#include "rocc.h"

int main() {
  uint64_t x = 123, y = 456, z = 0;
  // load x into accumulator 2 (funct=0)
  // See rocc.h for details but roughly:
  // I_R_I => rd (Immediate), rs1 (Register), rs2 (Immediate)
  // opcode=0, rd=0 (no return value), rs1=x (register), rs2=2 (immediate),
  // funct=0
  ROCC_INSTRUCTION_I_R_I(0, 0, x, 2, 0);

  // read it back into z (funct=1) to verify it
  ROCC_INSTRUCTION_R_I_I(0, z, 0, 2, 1);

  if(z != x) {
    printf("Failure!\n");
    return 1;
  }

  // accumulate 456 into it (funct=3)
  ROCC_INSTRUCTION_I_R_I(0, 0, y, 2, 3);

  // verify it
  ROCC_INSTRUCTION_R_I_I(0, z, 0, 2, 1);
  if(z != x+y) {
    printf("Failure!\n");
    return 1;
  }

  // do it all again, but initialize acc2 via memory this time (funct=2)
  ROCC_INSTRUCTION_I_R_I(0, 0, &x, 2, 2);
  ROCC_INSTRUCTION_I_R_I(0, 0, y, 2, 3);
  ROCC_INSTRUCTION_R_I_I(0, z, 0, 2, 1);
  if(z != x+y) {
    printf("Failure!\n");
    return 1;
  }

  printf("success!\n");
  return 0;
}
