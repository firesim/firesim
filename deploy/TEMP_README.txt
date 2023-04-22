- https://xilinx.github.io/XRT/2022.1/html/hm.html#host-memory-access
	- make sure that you run setup the size to larger than the dma allocation size (depends on target)
- Using following vitis example: https://github.com/Xilinx/Vitis_Accel_Examples/blob/master/host_xrt/host_memory_simple_xrt/src/host.cpp

TODO:
- Try to use the xclbinutil to look at the xclbin of the host memory copy xclbin (see what is off)
