#!/bin/bash
# Usage: ./mk_cpio.sh source_image.img output.cpio
set -e

MOUNT=./disk-mount
RAW_IMG=$1
CPIO=$2

sudo mount $RAW_IMG $MOUNT
cd $MOUNT 
sudo ln -s -f /sbin/init init
sudo find -print0 | sudo cpio --null -ov --format=newc > ../$CPIO 
sudo rm init
cd ../ 
sudo umount $MOUNT 
