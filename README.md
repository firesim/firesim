firesim-software
==================================

Builds a linux distro for firesim target nodes. Assumes priv 1.10.

## Prereqs:

    riscv-tools + riscv-gnu-toolchain for linux

## How to use:

    ./build.sh

## How to maintain/bump:

    buildroot: sifive-next branch
    pk: use commit hash from firesim/sim/midas-top/rocket-chip/riscv-tools/riscv-pk
    linux: riscv-next branch

## Useful notes:

* http://free-electrons.com/pub/conferences/2013/kernel-recipes/rootfs-kernel-developer/rootfs-kernel-developer.pdf
