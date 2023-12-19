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

#ifndef INTEL_FPGA_PCIE_DMA_H
#define INTEL_FPGA_PCIE_DMA_H

#include "intel_fpga_pcie_setup.h"

#define DMA_CTRL_BAR 0
#define DMA_RD_IDX 0
#define DMA_WR_IDX 1
#define DMA_TIMEOUT 0x2000000

#ifdef DMA_SUPPORTED
#if (DMA_VERSION_MAJOR == 1)

#define DMA_CTRL_BAR 0
#define MAX_NUM_DESCS 128
#define ONCHIP_MEM_BASE 0x0ULL

// Read and write are from host's perspective.
#define RDESC_FIFO_ADDR     0x01002000ULL
#define WDESC_FIFO_ADDR     0x01000000ULL
#define RC_DESC_BASE_L_REG_OFFSET 0x000
#define RC_DESC_BASE_H_REG_OFFSET 0x004
#define EP_FIFO_BASE_L_REG_OFFSET 0x008
#define EP_FIFO_BASE_H_REG_OFFSET 0x00C
#define LPTR_REG_OFFSET           0x010
#define DT_SIZE_REG_OFFSET        0x014
// Transfer from card to system - register offset
#define DMA_FROM_DEVICE_REG_OFFSET 0x100
// Transfer from system to card - register offset
#define DMA_TO_DEVICE_REG_OFFSET 0

/* End if DMA_VERSION_MAJOR == 1 */
#elif (DMA_VERSION_MAJOR == 4)

#define DMA_CTRL_BAR 0
#define MAX_NUM_DESCS 132
#define ONCHIP_MEM_BASE         0x00010000ULL
#define WRITE_DESC_NORM_OFFSET  0x000ULL
#define WRITE_DESC_PRIO_OFFSET  0x200ULL
#define READ_DESC_NORM_OFFSET   0x800ULL
#define READ_DESC_PRIO_OFFSET   0xA00ULL

#endif /* End if DMA_VERSION_MAJOR==4 */

/* End ifdef DMA_SUPPORTED */
#else
#define MAX_NUM_DESCS 1
#endif /* End ifndef DMA_SUPPORTED */

struct desc_status {
  volatile uint32_t status[128];
} __attribute__ ((packed)); // Aligned to 4-byte boundary

struct desc {
  uint64_t src_addr;  // Card Address for C2S transfers, System Address for S2C transfers: in little-endian format
  uint64_t dst_addr;  // System Address for C2S transfers and vice-versa: in little-endian format
  uint32_t ctrl;      // Transfer Size in dwords plus control info, little-endian format
  uint32_t reserved[3];
} __attribute__ ((packed));

struct desc_table {
  struct desc_status header;
  struct desc descriptor[MAX_NUM_DESCS];
} __attribute__ ((packed));


int intel_fpga_pcie_dma_probe(struct dev_bookkeep *dev_bk);
void intel_fpga_pcie_dma_remove(struct dev_bookkeep *dev_bk);
int intel_fpga_pcie_dma_queue(struct dev_bookkeep *dev_bk, unsigned long uarg);
int intel_fpga_pcie_dma_send(struct dev_bookkeep *dev_bk, unsigned long uarg);

#endif /* INTEL_FPGA_PCIE_DMA_H */