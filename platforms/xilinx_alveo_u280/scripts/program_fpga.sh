#!/usr/bin/env bash

# Adapted from https://github.com/Xilinx/open-nic-shell

echo $#
if [[ $# -le 1 ]] || [[ -z EXTENDED_DEVICE_BDF1 ]] || [[ -z $XILINX_VIVADO ]]; then
    echo "Usage: EXTENDED_DEVICE_BDF1=<EXTENDED_DEVICE_BDF1> program_fpga.sh BITSTREAM_PATH BOARD [PROBES_PATH]"
    echo "Please export EXTENDED_DEVICE_BDF1 and [EXTENDED_DEVICE_BDF2 (if needed for 2 port boards)]"
    echo "Example: EXTENDED_DEVICE_BDF1=<0000:86:00.0> program_fpga.sh BITSTREAM_PATH BOARD [PROBES_PATH]"
    echo "Please ensure vivado is loaded into system path."
    exit 1
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

set -Eeuo pipefail
set -x

bridge_bdf=""
bitstream_path=$1
board=$2
probes_path="${3:-}"
# ^^ Probes are used for specifying hardware debugger symbols.

# Infer bridge
if [ -e "/sys/bus/pci/devices/$EXTENDED_DEVICE_BDF1" ]; then
    bridge_bdf=$(basename $(dirname $(readlink "/sys/bus/pci/devices/$EXTENDED_DEVICE_BDF1")))
    # Both devices will be on the same bridge as they are on the same FPGA board.
fi

# Remove
if [[ $bridge_bdf != "" ]]; then
    echo 1 | sudo tee "/sys/bus/pci/devices/${bridge_bdf}/${EXTENDED_DEVICE_BDF1}/remove" > /dev/null
    if [[ -n "${EXTENDED_DEVICE_BDF2:-}" ]] && [[ -e "/sys/bus/pci/devices/${bridge_bdf}/${EXTENDED_DEVICE_BDF2}" ]]; then
        echo 1 | sudo tee "/sys/bus/pci/devices/${bridge_bdf}/${EXTENDED_DEVICE_BDF2}/remove" > /dev/null
    fi
else
    echo "Could not find bridge_bdf for the device $EXTENDED_DEVICE_BDF1"
    echo "If remove was called on the device already, then manually set bridge_bdf here and comment 'exit 1'."

    exit 1
fi

# Program fpga
vivado -mode tcl -source $SCRIPT_DIR/program_fpga.tcl \
    -tclargs -board $board \
    -bitstream_path $bitstream_path \
    -probes_path $probes_path

# Rescan
echo 1 | sudo tee "/sys/bus/pci/devices/${bridge_bdf}/rescan" > /dev/null
sudo setpci -s $EXTENDED_DEVICE_BDF1 COMMAND=0x02
if [[ -n "${EXTENDED_DEVICE_BDF2:-}" ]]; then
    sudo setpci -s $EXTENDED_DEVICE_BDF2 COMMAND=0x02
fi

echo "program_fpga.sh completed"
echo "Warm reboot machine if the FPGA wasn't initially setup with an XDMA bitstream."
