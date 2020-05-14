#!/bin/bash
set -e

if [ ! -d riscv-pk ]; then
  rsync --exclude ".git" -r ../../boards/default/firmware/riscv-pk .
  patch riscv-pk/bbl/bbl.c < test.patch
fi
