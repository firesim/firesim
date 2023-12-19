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

#ifndef INTEL_FPGA_PCIE_H
#define INTEL_FPGA_PCIE_H

#define DEBUG
#define VERBOSE_DEBUG

/* Debugging defines */
#define INTEL_FPGA_PCIE_PRINT(level,...)                    \
    do {                                                    \
        printk(level "%s - %04d: ", __func__, __LINE__);    \
        printk(__VA_ARGS__);                                \
        printk("\n");                                       \
    } while (0)


#define INTEL_FPGA_PCIE_ERR(...)                            \
    INTEL_FPGA_PCIE_PRINT(KERN_ERR, __VA_ARGS__)
#define INTEL_FPGA_PCIE_WARN(...)                           \
    INTEL_FPGA_PCIE_PRINT(KERN_WARNING, __VA_ARGS__)

#ifdef DEBUG
#  define INTEL_FPGA_PCIE_DEBUG(...)                        \
    INTEL_FPGA_PCIE_PRINT(KERN_DEBUG, __VA_ARGS__)
#else /* !DEBUG */
#  define INTEL_FPGA_PCIE_DEBUG(...)                        \
({                                                          \
    if (0) {                                                \
        INTEL_FPGA_PCIE_PRINT(KERN_DEBUG, __VA_ARGS__);     \
    }                                                       \
})
#endif /* !DEBUG */

#ifdef VERBOSE_DEBUG
#  define INTEL_FPGA_PCIE_VERBOSE_DEBUG(...)                \
    INTEL_FPGA_PCIE_DEBUG(__VA_ARGS__)
#else /* !VERBOSE_DEBUG */
#  define INTEL_FPGA_PCIE_VERBOSE_DEBUG(...)                \
({                                                          \
    if (0) {                                                \
        INTEL_FPGA_PCIE_DEBUG(__VA_ARGS__);                 \
    }                                                       \
})
#endif /* !VERBOSE_DEBUG */


#endif /* INTEL_FPGA_PCIE_H */
