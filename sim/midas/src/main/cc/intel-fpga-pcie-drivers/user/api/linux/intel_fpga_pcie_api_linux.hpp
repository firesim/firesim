// (C) 2017-2018 Intel Corporation.
//
// Intel, the Intel logo, Intel, MegaCore, NIOS II, Quartus and TalkBack words
// and logos are trademarks of Intel Corporation or its subsidiaries in the
// U.S. and/or other countries. Other marks and brands may be claimed as the
// property of others. See Trademarks on intel.com for full list of Intel
// trademarks or the Trademarks & Brands Names Database (if Intel) or see
// www.intel.com/legal (if Altera). Your use of Intel Corporation's design
// tools, logic functions and other software and tools, and its AMPP partner
// logic functions, and any output files any of the foregoing (including
// device programming or simulation files), and any associated documentation
// or information are expressly subject to the terms and conditions of the
// Altera Program License Subscription Agreement, Intel MegaCore Function
// License Agreement, or other applicable license agreement, including,
// without limitation, that your use is for the sole purpose of programming
// logic devices manufactured by Intel and sold by Intel or its authorized
// distributors. Please refer to the applicable agreement for further details.

#ifndef INTEL_FPGA_PCIE_API_LINUX_HPP
#define INTEL_FPGA_PCIE_API_LINUX_HPP


/**
 * struct intel_fpga_pcie_cmd - Main structure for user to communicate to
 *                              kernel the target and source of a read or
 *                              write transaction.
 */
struct intel_fpga_pcie_cmd {
    /** @bar_num: BAR to be targeted for the transaction. */
    unsigned int bar_num;

    /** @bar_offset: Byte offset within BAR to target. */
    uint64_t bar_offset;

    /**
     * @user_addr: Address in user-space to write/read data.
     *             Always a virtual address. Used as the source of write data
     *             and the destination of the read data.
     */
    void *user_addr;
} __attribute__ ((packed));


/**
 * struct intel_fpga_pcie_arg - Main structure to accommodate non-standard IO
 *                             accesses such as configuration read/write.
 */
struct intel_fpga_pcie_arg {
    /** @ep_addr: Address in end-point's BAR; byte offset within a BAR. */
    uint64_t ep_addr;

    /**
     * @user_addr: Address in user-space to write/read data.
     *             Always a virtual address. Used as the source of write data
     *             and the destination of the read data.
     */
    void *user_addr;

    /** @size: Size to be transferred, in bytes. */
    uint32_t size;

    /** @is_read: True if access is a read (user-perspective). */
    bool     is_read;
} __attribute__ ((packed));


#include <linux/ioctl.h>
#include <linux/types.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>

#define INTEL_FPGA_PCIE_IOCTL_MAGIC         0x70
#define INTEL_FPGA_PCIE_IOCTL_CHR_SEL_DEV   _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  0, unsigned int)
#define INTEL_FPGA_PCIE_IOCTL_CHR_GET_DEV   _IOR (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  1, unsigned int *)
#define INTEL_FPGA_PCIE_IOCTL_CHR_SEL_BAR   _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  2, unsigned int)
#define INTEL_FPGA_PCIE_IOCTL_CHR_GET_BAR   _IOR (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  3, unsigned int *)
#define INTEL_FPGA_PCIE_IOCTL_CHR_USE_CMD   _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  4, bool)
#define INTEL_FPGA_PCIE_IOCTL_CFG_ACCESS    _IOWR(INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  5,                           \
                                                  struct intel_fpga_pcie_arg *)
#define INTEL_FPGA_PCIE_IOCTL_SRIOV_NUMVFS  _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  6, unsigned int)
#define INTEL_FPGA_PCIE_IOCTL_SET_KMEM_SIZE _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  7, unsigned int)
#define INTEL_FPGA_PCIE_IOCTL_DMA_QUEUE     _IOWR(INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  8,                           \
                                                  struct intel_fpga_pcie_arg *)
#define INTEL_FPGA_PCIE_IOCTL_DMA_SEND      _IOW (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  9, unsigned int)
#define INTEL_FPGA_PCIE_IOCTL_GET_KTIMER    _IOR (INTEL_FPGA_PCIE_IOCTL_MAGIC, \
                                                  10, unsigned int *)
#define INTEL_FPGA_PCIE_IOCTL_MAXNR 10



#endif // INTEL_FPGA_PCIE_API_LINUX_HPP