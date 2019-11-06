#!/bin/bash
# Initialize all marshal-specific submodules. Skip submodules in
# example-workloads (if any). Users should always manually initialize workloads
# if they need them.
#
# You do not need to call this script if you only intend to build bare-metal workloads.

git submodule update --init \
  riscv-linux \
  riscv-pk \
  wlutil/busybox \
  wlutil/br/buildroot \
  boards/firechip/drivers/*
