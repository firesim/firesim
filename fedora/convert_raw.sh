#!/bin/bash
# This script converts an upstream fedora image to one suitable for Firesim use.
# This is only needed if the fedora team releases a new pre-built image with some
# critical feature (should be rare). Note that this assumes the current (circa
# Oct '18) images, you may need to tweak this.
#
# Usage: ./convert_img.sh UPSTREAM_IMAGE NEW_FILE_NAME

set -e

RAWIMG=$1
NEWIMG=$2
MNT=disk-mount/

if [ -f $NEWIMG ]; then
  read -p "Overwrite existing image \"$NEWIMG\"? (y/n) " OVERWRITE_IMG
  if [ $OVERWRITE_IMG != "y" ]; then
    echo "Aborting"
    exit
  fi
  rm $NEWIMG
fi

# Be sure to cleanup mounts if something goes wrong
function cleanup() {
  trap - ERR
  if [ $LOOP_DEV != "" ]; then
    sudo losetup -d $LOOP_DEV
  fi
}
trap cleanup ERR

# Attach the upstream image to a loopback device to get at the partition
echo "Converting image to single partition and resizing"
LOOP_DEV="$(sudo losetup -f)"
sudo losetup -P -f $RAWIMG $LOOP_DEV

# Copy the partition out of the upstream image (touch to establish current user as owner)
touch $NEWIMG
sudo dd if=${LOOP_DEV}p1 of=$NEWIMG bs=4M

# clean up the loopback interface
sudo losetup -d $LOOP_DEV
trap - ERR
# Don't keep the raw image around, it's big and pointless. But cache the compressed one just in case.
rm $RAWIMG

# Setup the image how we want for firesim 
echo "Setting up image for firesim"
sudo mount -o loop $NEWIMG $MNT

# fix serial port
sudo cp ./getty@.service $MNT/usr/lib/systemd/system/
sudo chmod 644 $MNT/usr/lib/systemd/system/getty@.service
sudo ln -s /usr/lib/systemd/system/getty@.service $MNT/etc/systemd/system/getty.target.wants/getty@hvc0.service
sudo rm $MNT/etc/systemd/system/getty.target.wants/getty@tty1.service

sudo umount $MNT 
