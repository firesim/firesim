#!/usr/bin/env bash

#NOTE: using the disk image modified to use /dev/hvc0 console will act weirdly
#on qemu (you may see some garbage being printed), but it will reach the prompt

qemu-system-riscv64 \
    -nographic \
    -machine virt \
    -smp 4 \
    -m 16G \
    -kernel QEMU-ONLY-bbl \
    -object rng-random,filename=/dev/urandom,id=rng0 \
    -device virtio-rng-device,rng=rng0 \
    -append "console=ttyS0 ro root=/dev/vda" \
    -device virtio-blk-device,drive=hd0 \
    -drive file=stage4-disk.img,format=raw,id=hd0 \
    -device virtio-net-device,netdev=usernet \
    -netdev user,id=usernet,hostfwd=tcp::10000-:22
