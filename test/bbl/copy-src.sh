#!/bin/bash
set -e

if [ ! -d linux ]; then
  rsync --exclude ".git" -r ../../boards/firechip/firmware/riscv-pk .
  patch riscv-pk/bbl/bbl.c < test.patch
fi
