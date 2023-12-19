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

#ifndef INTEL_FPGA_PCIE_CHR_H
#define INTEL_FPGA_PCIE_CHR_H

#include "intel_fpga_pcie_setup.h"

/*
 * Any transfer using the character device is limited to 64B max.
 *
 * This limit allows the code to create a local array to use as data bounce
 * buffer. Avoiding the use of malloc is crucial to reducing any unneeded
 * overhead and the local array needs to have a reasonable size.
 */
#define MAX_TRANSFER_SIZE 64


int __init intel_fpga_pcie_chr_init(void);
void intel_fpga_pcie_chr_exit(void);


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

#endif /* INTEL_FPGA_PCIE_CHR_H */