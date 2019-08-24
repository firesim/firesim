#!/bin/bash

# Usage:
#   fakeroot -- ./makeInitramfs.sh

# This script turns initramfsRoot/ into a proper cpio archive of a bootable
# initramfs. It must be run in a fakeroot environment in order to create the
# special device files /dev/console and /dev/tty before packaging..

pushd initramfsRoot/

echo "Making Nodes"
mknod -m 622 dev/console c 5 1
mknod -m 666 /dev/tty c 5 0

echo "Building initrd"
find -print0 | cpio --owner root:root --null -ov --format=newc > ../initramfsRoot.cpio

exit
