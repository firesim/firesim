firesim-software
==================================

Builds a linux distro for firesim target nodes. Assumes priv 1.10.

## Prereqs:

    riscv-tools + riscv-gnu-toolchain for linux

## How to use:

    ./build.sh

## Starting up simplenic (once you're running linux on firesim):

    ip link set eth0 up
    ip addr add 192.168.1.2/24 dev eth0

## Setting up the tap device for simplenic to talk to on your host machine:

    sudo ip tuntap add mode tap dev tap0 user $USER
    sudo ip link set tap0 up
    sudo ip addr add 192.168.1.1/24 dev tap0

## How to maintain/bump:

    buildroot: sifive-next branch
    pk: use commit hash from firesim/sim/midas-top/rocket-chip/riscv-tools/riscv-pk
    linux: riscv-next branch

## Useful notes:

* http://free-electrons.com/pub/conferences/2013/kernel-recipes/rootfs-kernel-developer/rootfs-kernel-developer.pdf
