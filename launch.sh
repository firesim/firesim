#!/bin/bash

if [ $# -eq 0 ]; then
  PLATFORM=buildroot
else
  PLATFORM=$1
fi

qemu-system-riscv64  -nographic  \
  -machine virt  \
  -smp 4 \
  -m 4G  \
  -kernel ${PLATFORM}-bin \
  -object rng-random,filename=/dev/urandom,id=rng0 \
  -device virtio-rng-device,rng=rng0 \
  -append "console=ttyS0 ro root=/dev/vda" \
  -device virtio-blk-device,drive=hd0  \
  -drive file=${PLATFORM}-rootfs.img,format=raw,id=hd0 \
  -device virtio-net-device,netdev=usernet \
  -netdev user,id=usernet,hostfwd=tcp::10000-:22 \
  -s
