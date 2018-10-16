#!/bin/bash
set -e

# overwrite buildroot's config with ours, then build rootfs
cp buildroot-config buildroot/.config
cd buildroot
# Note: buildroot doesn't support make -jN, but it does parallelize anyway.
make -j1
cd ..
cp buildroot/output/images/rootfs.ext2 rootfs.img
