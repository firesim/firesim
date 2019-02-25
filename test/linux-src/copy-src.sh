#!/bin/bash
if [ ! -d riscv-linux ]; then
  rsync --exclude ".git" -r ../../riscv-linux .
  patch riscv-linux/kernel/reboot.c < test.patch
fi
