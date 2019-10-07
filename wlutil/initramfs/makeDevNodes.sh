#!/bin/bash

# Usage:
#   fakeroot -- ./makeInitramfs.sh

# This script turns initramfsRoot/ into a proper cpio archive of a bootable
# initramfs. It must be run in a fakeroot environment in order to create the
# special device files /dev/console and /dev/tty before packaging..

orig_pwd=${PWD}
tmp_dir=$(mktemp -d -t ci-XXXXXXXXXX)

pushd $tmp_dir

echo "Making Nodes"
mkdir dev
mknod -m 622 dev/console c 5 1
mknod -m 666 dev/tty c 5 0
mknod -m 666 dev/null c 1 3

echo "Building initrd"
find -print0 | cpio --owner root:root --null -ov --format=newc > $orig_pwd/devNodes.cpio
popd

rm -r $tmp_dir

exit
