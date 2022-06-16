#!/usr/bin/env bash

# WARNING: This script is meant to be used by FireSim developers only. Use at your own risk.

devices=(0000:41:00.0 0000:3d:00.0 0000:1e:00.0 0000:1b:00.0)
path_to_shell=/lib/firmware/xilinx/bd5fb8abab266c3265918257b5048e88/partition.xsabin


for device in "${devices[@]}"; do
    /opt/xilinx/xrt/bin/xbmgmt program --shell $path_to_shell -d $device
done

/opt/xilinx/xrt/bin/xbmgmt examine

