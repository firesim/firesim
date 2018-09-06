#!/bin/bash
set -e

if [ $# -ne 0 ]; then
  PLATFORM=$1
  if [ $1 == "spike" ]; then
    LINUX_CONFIG=linux-config-spike
  elif [ $1 == "firesim" ]; then
    LINUX_CONFIG=linux-config-firesim
  elif [ $1 == "initramfs" ] ; then
    LINUX_CONFIG=linux-config-initramfs
  elif [ $1 == "fedora-initramfs" ] ; then
    if [ ! -f ../../deploy/workloads/fedora-uniform/stage4-disk.img ]; then
      echo "This software target assumes it is being built within the main FireSim project directory tree, and that the fedora-uniform workload has been fully generated within the deploy/workloads directory"
      exit 1
    fi
    LINUX_CONFIG=linux-config-fedora-initramfs
    sudo mount ../../deploy/workloads/fedora-uniform/stage4-disk.img ../../deploy/workloads/fedora-uniform/fedoramount/
    cd ../../deploy/workloads/fedora-uniform/fedoramount
    sudo ln -s -f /sbin/init init
    sudo find . -print0 | sudo cpio --null -ov --format=newc > /tmp/initramfs.cpio
    cd ../../../../sw/firesim-software
    sudo umount ../../deploy/workloads/fedora-uniform/fedoramount/
  else
    echo "Please provide a valid platform (or no arguments to default to firesim)"
    exit 1
  fi
else
  PLATFORM="firesim"
  LINUX_CONFIG=linux-config-firesim
fi

export MAKEFLAGS=-j16

# overwrite buildroot's config with ours, then build rootfs
cp buildroot-config buildroot/.config
cd buildroot
# Note: buildroot doesn't support make -jN, but it does parallelize anyway.
make -j1
cd ..
cp buildroot/output/images/rootfs.ext2 rootfs0.ext2

# overwrite linux's config with ours, then build vmlinux image
cp $LINUX_CONFIG riscv-linux/.config
cd riscv-linux
make -j16 ARCH=riscv vmlinux
cd ..

# build pk, provide vmlinux as payload
cd riscv-pk
mkdir -p build
cd build
../configure --host=riscv64-unknown-elf --with-payload=../../riscv-linux/vmlinux
make -j16
cp bbl ../../bbl-vmlinux0

if [ $PLATFORM == "firesim" ]; then
  # make 7 more copies of the rootfs for f1.16xlarge nodes
  cd ../../
  for i in {1..7}
  do
      cp bbl-vmlinux0 bbl-vmlinux$i
      cp rootfs0.ext2 rootfs$i.ext2
  done
fi
