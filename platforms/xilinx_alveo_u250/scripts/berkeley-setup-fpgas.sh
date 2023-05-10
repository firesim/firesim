#!/bin/bash

set -ex

BITSTREAM=$SCRATCH_HOME/firesim-private/deploy/results-build/2023-05-12--07-11-08-alveou250_firesim_rocket_singlecore_no_nic_w_pres/cl_xilinx_alveo_u250-firesim-FireSim-FireSimRocketConfig-BaseXilinxAlveoConfig/xilinx_alveo_u250/firesim.bit

./program_fpga.py --bdf 04:00.0 --disconnect-bdf
./program_fpga.py --bdf 83:00.0 --disconnect-bdf

if lspci | grep -i xilinx; then
    echo "Something went wrong"
    exit 1
else
    echo "Missing them"
fi

./program_fpga.py --serial_no Xilinx/21320733400EA --bitstream $BITSTREAM
./program_fpga.py --serial_no Xilinx/213207334001A --bitstream $BITSTREAM

if lspci | grep -i xilinx; then
    echo "Something went wrong"
    exit 1
else
    echo "Missing them"
fi

./program_fpga.py --bdf 04:00.0 --reconnect-bdf
./program_fpga.py --bdf 83:00.0 --reconnect-bdf

if lspci | grep -i xilinx; then
    echo "Found them"
else
    echo "Something went wrong"
    exit 1
fi

## from scratch
#
#./program_fpga.py --bdf 04:00.0 --disconnect-bdf
#./program_fpga.py --bdf 04:00.1 --disconnect-bdf
#./program_fpga.py --bdf 83:00.0 --disconnect-bdf
#./program_fpga.py --bdf 83:00.1 --disconnect-bdf
#
#./program_fpga.py --serial_no Xilinx/21320733400EA --bitstream $BITSTREAM
#./program_fpga.py --serial_no Xilinx/213207334001A --bitstream $BITSTREAM
#
##./program_fpga.py --bdf 04:00.0 --reconnect-bdf
##./program_fpga.py --bdf 04:00.1 --reconnect-bdf
##./program_fpga.py --bdf 83:00.0 --reconnect-bdf
##./program_fpga.py --bdf 83:00.1 --reconnect-bdf
