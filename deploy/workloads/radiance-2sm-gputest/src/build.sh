#!/bin/bash
# Rebuild the two-board Radiance GPU smoke test. Run from chipyard with env.sh sourced.
# Output: ../gpu_test.riscv (unhashed, for TSI loading with +tsi-no-loadmem) and
#         ../gpu_test.hashed.riscv (GPU segments pre-hashed, for FireSim's default LoadMem path).
set -e
cd "$(dirname "$0")"
CY=${CY_DIR:-$(git rev-parse --show-toplevel)}
python3 -I encode_muon.py gpu_kernel.bin
riscv64-unknown-elf-gcc -std=gnu99 -O2 -fno-common -fno-builtin-printf -Wall -march=rv64imafdc -mabi=lp64d \
  -mcmodel=medany -specs=htif_nano.specs -T gpu_test.ld -I "$CY/tests" gpu_test.c gpu_blob.S -o ../gpu_test.riscv
python3 -I scramble_gpu_elf.py ../gpu_test.riscv ../gpu_test.hashed.riscv --check
