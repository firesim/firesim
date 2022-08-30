#!/usr/bin/env bash

RUN_DIR=$(pwd)
SCRIPT_DIR=$(dirname $(readlink -f $0))

FPGA=$1
WORKLOAD=$2

if [ -z ${FIRESIM_CONFIG+x} ]; then
	echo "Variable FIRESIM_CONFIG needs to be set!" >&2
	exit 1
fi

DRIVER="${SCRIPT_DIR}/../../../sim/output/u250/FireSim-${FIRESIM_CONFIG}/FireSim-u250"
RUNTIME_CONFIG="${SCRIPT_DIR}/../../../sim/output/u250/FireSim-${FIRESIM_CONFIG}/runtime.conf"
BITSTREAM="${SCRIPT_DIR}/../../../sim/generated-src/u250/FireSim-${FIRESIM_CONFIG}/u250/vivado_proj/firesim.runs/impl_1/design_1_wrapper.bit"
IMAGE_IMG="${RUN_DIR}/workload.img"
IMAGE_BIN="${RUN_DIR}/workload.bin"

for f in "${DRIVER}" "${BITSTREAM}" "${RUNTIME_CONFIG}" "${IMAGE_IMG}" "${IMAGE_BIN}"; do
	if [ ! -e "${f}" ]; then
		echo "Could not find file ${f}" >&2
		exit 1
	fi
done

PARAMETERS=$(cat "${RUNTIME_CONFIG}" | tr '\n' ' ')
CUSTOM_PARAMETERS=""

if [ ! -z ${CUSTOM_RUNTIME_CONFIG+x} ]; then
	if [ -e "${CUSTOM_RUNTIME_CONFIG}" ]; then
		CUSTOM_PARAMETERS=$(cat "${CUSTOM_RUNTIME_CONFIG}" | tr '\n' ' ')
	elif [ -e "${SCRIPT_DIR}/../${CUSTOM_RUNTIME_CONFIG}" ]; then
		CUSTOM_PARAMETERS=$(cat "${SCRIPT_DIR}/../${CUSTOM_RUNTIME_CONFIG}" | tr '\n' ' ')
	fi
fi

set -x
fpga-util.py -f "${FPGA}" -b "${BITSTREAM}"
${DRIVER} +permissive ${PARAMETERS} ${CUSTOM_PARAMETERS} +slotid="${FPGA}" +blkdev0="${IMAGE_IMG}" +permissive-off "${IMAGE_BIN}"
set +x


exit 0
