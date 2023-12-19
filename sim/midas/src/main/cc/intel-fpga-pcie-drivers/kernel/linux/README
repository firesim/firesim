Intel(R) FPGA PCI Express Driver for Linux, 18.0
------------------------------------------------
This directory contains full source code for the Intel(R) FPGA
PCI Express Driver for Linux.
Full concurrency is not yet supported.


PREREQUISITES
-------------
- GCC version in /usr/bin (the same version of gcc that was used to compile
  the kernel).

- Kernel include files, or the complete source. If the
  /usr/src/kernels/<version> is missing or does not contain
  a Makefile, install kernel-devel package by running
  "sudo yum install kernel-devel".


COMPIILE and INSTALL
--------------------

To compile and load the driver:
  sudo ./install

To unload the driver:
  sudo ./unload


OPERATION EXAMPLE
-----------------
Refer to api_linux.cpp on how to access the device.
For simplest access:
    1. Open device:
        ssize_t fd = open("/dev/intel_fpga_pcie_drv", O_RDWR | O_CLOEXEC);
    2. Select device using BDF:
        int result = ioctl(fd, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_DEV, some_bdf);
        if (result != 0) return;    // failed
    3. Select BAR:
        result = ioctl(fd, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_BAR, some_bar);
        if (result != 0) return;    // failed
    4. Do access:
        pwrite(fd, data_addr, sizeof(*data_addr), device_addr);
        pread(fd, dest_addr, sizeof(*dest_addr), device_addr);

Alternatively, if the BAR is frequently switched, the command structure
could be used:
    1. Switch to command structure mode:
        result = ioctl(fd, INTEL_FPGA_PCIE_IOCTL_CHR_USE_CMD, true);
        if (result != 0) return;    // failed
    2. Do access, with BAR specified:
        struct intel_fpga_pcie_cmd cmd;
        cmd.bar_num = some_bar;
        cmd.bar_offset = device_addr;
        cmd.user_addr = data_addr;
        write(fd, &cmd, access_size);
        cmd.user_addr = dest_addr;
        read(fd, &cmd, access_size);


TESTING
-------
The driver was developed and tested on CentOS 7.0, 64-bit with
3.10.514 kernel compiled for x86_64 architecture.


SOURCE CODE DESCRIPTION
-----------------------
intel_fpga_pcie_setup.c:
    The driver can be compiled as a module or integrated into the main kernel.
    If it is a module, it must be added to the kernel during runtime using
    insmod command. Once the driver is included in the OS, there are several
    requirements to allow the user-space to actually communicate with the
    PCIe device:
        - The kernel must know that the driver exists - having the driver as
          part of the kernel image is insufficient
        - The kernel must know if a new device can use the driver
        - The driver must expose a communication 'bridge' to the user - the
          bridge is a character device and/or a block device
        - The driver must keep track of the PCIe device information
    This file is responsible for ensuring all these requirements. Also, in
    the case that the driver is a module, this file allows for the proper
    removal of the module from the kernel.

intel_fpga_pcie_chr.c:
    This file implements a interface between the user-space and the
    kernel-space based on a character device. The PCIe device is
    represented as a virtual file system which can be read from/written to.
    Accessing the hardware is achieved through user-space system calls such
    as read(). Refer to Linux's manual such as
    http://man7.org/linux/man-pages/man2/read.2.html for more information
    on system calls related to character devices.

    /Documentation/filesystems/vfs.txt contains a wealth of information on
    which user system calls invoke which kernel calls, and how each kernel
    function is used (at a very high level).

intel_fpga_pcie_ioctl.c:
    This file services all non-standard device accesses or access
    configurations in response to ioctl(2) calls. Many of the requests are
    serviced directly within this file, while others are routed to the
    correct sub-system function call, such as SR-IOV enablement requests.
    There are largely three categories of functions:
        - Modify driver's settings, such as which device or BAR to access
        - Do configuration read/write to modify hardware settings, including
          modification of SR-IOV settings
        - DMA requests (in future release)


FEEDBACK
--------
