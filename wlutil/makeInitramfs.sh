#!/bin/bash

pushd initramfsRoot/

echo "Making Nodes"
mknod -m 622 dev/console c 5 1
mknod -m 666 /dev/tty c 5 0

echo "Building initrd"
find -print0 | cpio --owner root:root --null -ov --format=newc > ../initramfsRoot.cpio

exit
