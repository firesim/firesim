#!/bin/bash
# This script converts an upstream fedora image to one suitable for Firesim use.
# This is only needed if the fedora team releases a new pre-built image with some
# critical feature (should be rare). Note that this assumes the current (circa
# Oct '18) images, you may need to tweak this.
#
# Usage: ./convert_img.sh [-c] UPSTREAM_IMAGE NEW_FILE_NAME

set -e

USAGE="./convert_img.sh UPSTREAM_IMAGE NEW_FILE_NAME"
RAWIMG=$1
NEWIMG=$2
MNT=disk-mount/

while getopts ":c" opt; do
  case ${opt} in
    \? )
      echo $USAGE
      ;;
  esac
done

# Extract the partion
# use 1MB block size for dd
DD_BS=1048576

# Get the offset of the first partition within the image so we can strip it out
# output of parted looks like "###B ####B ###B ..." we cast it to an array here
PART_INFO=(`sudo parted -s $RAWIMG unit B print | tail -2`)

# The output at index 1 and 2 are the partition start and end byte offset. The : : -1 strips the "B" suffix.
# Also convert to units of 1MB blocks (I'd do that above, but parted output is weird)
PART_START=`expr ${PART_INFO[1]: : -1} / $DD_BS`
PART_END=`expr ${PART_INFO[2]: : -1} / $DD_BS`

dd if=$RAWIMG of=$NEWIMG bs=$DD_BS skip=$PART_START
