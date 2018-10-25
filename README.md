firesim-software
==================================

This builds base images for several linux-based distros that work with qemu,
spike, and firesim. 

## How to use:
All builds are controlled through json files. For example, br-disk.json will
build/run the disk-based buildroot distro.

Prereq/Init:
Be sure to update/init submodules. The following will require the riscv-tools
on your path and a recent version of qemu.

To build:
    ./sw-manager.py -c CONFIG.json build

To run on qemu:
  ./sw-manager.py -c CONFIG.json

To run on spike:
  ./sw-manager.py -c CONFIG.json -s

To run on FireSim:
See the firesim documentation.

Tl;Dr: images built with build.py will work on firesim, you just need to update
the symlinks in workloads/deploy/ and then run them as normal. This will be
intergrated more completely in future releases.
