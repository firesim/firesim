#!/bin/sh

RUN_DIR=$(pwd)
SCRIPT_DIR=$(dirname $(readlink -f $0))
FPGA=$1
WORKLOAD=$2

IMAGE_IMG="${RUN_DIR}/workload.img"
IMAGE_BIN="${RUN_DIR}/workload.bin"
RESULT_DIR="${RUN_DIR}/results"

if [ ! -e "${IMAGE_IMG}" ] || [ ! -e "${IMAGE_BIN}" ]; then
	echo "Could not find ${IMAGE_IMG} or ${IMAGE_BIN}" >&2
	exit 1
fi

if [ ! -d "${RESULT_DIR}" ]; then
	mkdir -p "${RESULT_DIR}"
	if [ ! -d "${RESULT_DIR}" ]; then
		echo "Could not create ${RESULT_DIR}" >&2
		exit 1
	fi
fi

set -x
tmp=$(mktemp -d)
guestmount -a "${IMAGE_IMG}" -m /dev/sda "${tmp}"
if [ -e "${tmp}/output" ]; then
	cp -vr "${tmp}/output" "${RESULT_DIR}/"
fi
guestunmount "${tmp}"

rm -fR "${tmp}"
rm -f "${IMAGE_IMG}"
rm -f "${IMAGE_BIN}"

gzip -v "${RUN_DIR}/"*.csv

set +x
exit 0
