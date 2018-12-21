firesim-software
==================================

This builds base images for several linux-based distros that work with qemu,
spike, and firesim. 

# How to use:
All builds are controlled through json files. For example, br-disk.json will
build/run the disk-based buildroot distro.

Prereq/Init:
Be sure to update/init submodules. The following will require the riscv-tools
on your path and a recent version of qemu.

To build:
    ./sw-manager.py -c CONFIG.json build

To run on qemu:
  ./sw-manager.py -c CONFIG.json launch

To run on spike:
  ./sw-manager.py -c CONFIG.json launch -s

To run on FireSim:
See the firesim documentation.

Tl;Dr: images built with build.py will work on firesim, you just need to update
the symlinks in workloads/deploy/ and then run them as normal. This will be
intergrated more completely in future releases.

## Requirements
This project was written for python 3.4

python-requirements.txt and centos-requirements.txt are incomplete lists of
required packages for python3. If you find that you need a package not in those
lists, please file an issue.

# Gotcha's and potentially unintuitive behavior
## Incremental Builds
It can be very frustrating to accidentally rebuild a complex workload from
scratch, especially if you've modified it in some what. sw-manager tries to
avoid this by performing incremental builds. Regardless of changes in the base
workload, or changes in your workload config, your image will only be
regenerated if you run 'clean' first. However, any image files ('overlay' or
'files') will still be rsync'd to the old image, and the "guest-init" script
will be re-run.

This means that you can still *lose changes to files that were specified in
your overlay of file list* if you rebuild the workload. You should also strive
to make your guest-init script as idempotent as possible (to avoid long delays
or destroying state on rebuild).

