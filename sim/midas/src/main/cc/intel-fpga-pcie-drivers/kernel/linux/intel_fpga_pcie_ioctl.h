/*
 * Copyright (c) 2017-2018, Intel Corporation.
 * Intel, the Intel logo, Intel, MegaCore, NIOS II, Quartus and TalkBack
 * words and logos are trademarks of Intel Corporation or its subsidiaries
 * in the U.S. and/or other countries. Other marks and brands may be
 * claimed as the property of others. See Trademarks on intel.com for
 * full list of Intel trademarks or the Trademarks & Brands Names Database
 * (if Intel) or see www.intel.com/legal (if Altera).
 * All rights reserved.
 *
 * This software is available to you under a choice of one of two
 * licenses. You may choose to be licensed under the terms of the GNU
 * General Public License (GPL) Version 2, available from the file
 * COPYING in the main directory of this source tree, or the
 * BSD 3-Clause license below:
 *
 *     Redistribution and use in source and binary forms, with or
 *     without modification, are permitted provided that the following
 *     conditions are met:
 *
 *      - Redistributions of source code must retain the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer.
 *
 *      - Redistributions in binary form must reproduce the above
 *        copyright notice, this list of conditions and the following
 *        disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *
 *      - Neither Intel nor the names of its contributors may be
 *        used to endorse or promote products derived from this
 *        software without specific prior written permission.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#ifndef INTEL_FPGA_PCIE_IOCTL_H
#define INTEL_FPGA_PCIE_IOCTL_H


#include "intel_fpga_pcie_setup.h"

/**
 * struct intel_fpga_pcie_arg - Main structure to accommodate non-standard IO
 *                              accesses such as configuration read/write.
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
                                                  6, int)
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



long intel_fpga_pcie_unlocked_ioctl(struct file *filp, unsigned int cmd,
                                    unsigned long arg);

#endif /* INTEL_FPGA_PCIE_IOCTL_H */