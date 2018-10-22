firesim-software
==================================

This builds base images for several linux-based distros that work with both
qemu and firesim. 

## How to use:
All builds are controlled through json files. For example, br-disk.json will
build/run the disk-based buildroot distro.

To build:
    [ requires risc-v tools on path]
    git submodule update --init
    ./build.py CONFIG.json

To run on qemu:
  ./launch.py CONFIG.json

To run on FireSim see the firesim documentation. Tl;Dr: images built with
build.py will work on firesim, you just need to update the symlinks in
workloads/deploy/ and then run them as normal. This will be intergrated more
completely in future releases.
