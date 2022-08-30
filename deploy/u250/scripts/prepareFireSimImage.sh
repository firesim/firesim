#!/bin/sh

RUN_DIR=$(pwd)
SCRIPT_DIR=$(dirname $(readlink -f $0))

FPGA=$1
WORKLOAD=$2

IMAGE_IMG="${SCRIPT_DIR}/../common/images/${WORKLOAD}.img"
IMAGE_BIN="${SCRIPT_DIR}/../common/images/${WORKLOAD}-bin"

if [ ! -e "${IMAGE_IMG}" ] || [ ! -e "${IMAGE_BIN}" ]; then
	echo "Could not find ${IMAGE_IMG} or ${IMAGE_BIN}" >&2
	exit 1
fi

set -x
cp -aL "${IMAGE_IMG}" "${RUN_DIR}/workload.img" || exit 1
cp -aL "${IMAGE_BIN}" "${RUN_DIR}/workload.bin" || exit 1
set +x
exit 0
