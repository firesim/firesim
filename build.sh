#!/bin/bash
set -e

if [ $# -eq 0 ]; then
  PLATFORM=buildroot
else
  PLATFORM=$1
fi

if [ $1 == "buildroot" ]; then
  DIST_DIR=buildroot/
elif [ $1 == "fedora" ] ; then
  DIST_DIR=fedora
else
  echo "Please provide a valid platform (or no arguments to default to buildroot)"
  exit 1
fi

# Get rootfs from distro
pushd $DIST_DIR
make rootfs.img
cp rootfs.img ../$PLATFORM-rootfs.img
popd

# overwrite linux's config with ours, then build vmlinux image
cp $DIST_DIR/linux-config riscv-linux/.config
pushd riscv-linux
make -j16 ARCH=riscv vmlinux
pushd ..

# build pk, provide vmlinux as payload
pushd riscv-pk
mkdir -p build
cd build
../configure --host=riscv64-unknown-elf --with-payload=../../riscv-linux/vmlinux
make -j16
cp bbl ../../$PLATFORM-bin
popd
