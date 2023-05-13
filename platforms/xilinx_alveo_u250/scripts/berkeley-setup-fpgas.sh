#!/bin/bash

set -ex

BITSTREAM=$SCRATCH_HOME/firesim-private/deploy/results-build/2023-05-12--07-11-08-alveou250_firesim_rocket_singlecore_no_nic_w_pres/cl_xilinx_alveo_u250-firesim-FireSim-FireSimRocketConfig-BaseXilinxAlveoConfig/xilinx_alveo_u250/firesim.bit

./fpga-util.py --bdf 04:00.0 --disconnect-bdf
./fpga-util.py --bdf 83:00.0 --disconnect-bdf

if lspci | grep -i xilinx; then
    echo "Something went wrong"
    exit 1
else
    echo "Missing them"
fi

./fpga-util.py --serial Xilinx/21320733400EA --bitstream $BITSTREAM
./fpga-util.py --serial Xilinx/213207334001A --bitstream $BITSTREAM

if lspci | grep -i xilinx; then
    echo "Something went wrong"
    exit 1
else
    echo "Missing them"
fi

./fpga-util.py --bdf 04:00.0 --reconnect-bdf
./fpga-util.py --bdf 83:00.0 --reconnect-bdf

if lspci | grep -i xilinx; then
    echo "Found them"
else
    echo "Something went wrong"
    exit 1
fi

## from scratch
#
#./fpga-util.py --bdf 04:00.0 --disconnect-bdf
#./fpga-util.py --bdf 04:00.1 --disconnect-bdf
#./fpga-util.py --bdf 83:00.0 --disconnect-bdf
#./fpga-util.py --bdf 83:00.1 --disconnect-bdf
#
#./fpga-util.py --serial Xilinx/21320733400EA --bitstream $BITSTREAM
#./fpga-util.py --serial Xilinx/213207334001A --bitstream $BITSTREAM
#
##./fpga-util.py --bdf 04:00.0 --reconnect-bdf
##./fpga-util.py --bdf 04:00.1 --reconnect-bdf
##./fpga-util.py --bdf 83:00.0 --reconnect-bdf
##./fpga-util.py --bdf 83:00.1 --reconnect-bdf
