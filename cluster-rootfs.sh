#!/bin/bash

# produce 8 rootfses for cluster sim

producerootfs () {
    sed -i "s/address 192.168.1.*/address 192.168.1.1$1/" buildroot-overlay/etc/network/interfaces
    cd buildroot
    make -j16
    cd ..
    cp buildroot/output/images/rootfs.ext4 rootfs$1.ext4
}

producerootfs 0
producerootfs 1
producerootfs 2
producerootfs 3
producerootfs 4
producerootfs 5
producerootfs 6
producerootfs 7
