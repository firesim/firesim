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

#include "intel_fpga_pcie.h"
#include "intel_fpga_pcie_dma.h"
#include "intel_fpga_pcie_ioctl.h"
#include "intel_fpga_pcie_chr.h"

#ifdef DMA_SUPPORTED
/******************************************************************************
 * Static function prototypes
 *****************************************************************************/
static void dma_init(struct dev_bookkeep *dev_bk);
static void dma_start(struct dev_bookkeep *dev_bk, uint8_t lptr,
                      bool lptr_wrap, bool is_read);
static void set_desc(struct desc *desc, uint64_t ep_addr, dma_addr_t rp_addr,
                     uint32_t byte_len, uint32_t id, bool is_read);
static int intel_fpga_pcie_interrupt_probe(struct dev_bookkeep *dev_bk);
static void intel_fpga_pcie_interrupt_remove(struct dev_bookkeep *dev_bk);

/******************************************************************************
 * Main DMA operation functions
 *****************************************************************************/
int intel_fpga_pcie_dma_queue(struct dev_bookkeep *dev_bk, unsigned long uarg)
{
    struct intel_fpga_pcie_arg karg;
    uint64_t ep_addr;
    uintptr_t kmem_offset;
    uint32_t size;
    bool is_read;
    struct kmem_info *kmem_info;
    struct dma_info *dma_info;
    int dma_idx;
    uint8_t lptr;

    if (copy_from_user(&karg, (void *)uarg, sizeof(karg))) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy arg from user.");
        return -EFAULT;
    }

    kmem_info = &dev_bk->kmem_info;
    dma_info  = &dev_bk->dma_info;
    ep_addr   = karg.ep_addr + ONCHIP_MEM_BASE;
    kmem_offset = (uintptr_t)karg.user_addr;
    size      = karg.size;
    is_read   = karg.is_read;
    dma_idx   = is_read ? DMA_RD_IDX : DMA_WR_IDX;

    // Check input arguments
    if (unlikely((size % 4) || (size < 4) || size > (1*1024*1024))) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested DMA transfer length "
                                      "is invalid.");
        return -EINVAL;
    }

    // TODO: EP address range check.
    // if (unlikely(size >= DMA_MEM_SIZE ||
    //              ep_addr > DMA_MEM_OFFSET ||
    //              ep_addr > DMA_MEM_OFFSET
    //              )) {
    //     INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested end point memory address "
    //                                   "range is invalid.");
    //     return -EINVAL;
    // }

    if (unlikely((kmem_offset+size) > kmem_info->size)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested kernel memory address range "
                                      "is invalid.");
        return -EINVAL;
    }

    if (unlikely(dma_info->num_pending[dma_idx] == 128)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("no unused descriptor available.");
        return -EBUSY;
    }
    lptr = dma_info->last_ptr[dma_idx];

    if (lptr >= 127) {
        if (dma_info->num_pending[dma_idx] > 0) {
            dma_info->last_ptr_wrap[dma_idx] = true;
        }
        lptr = 0;
    } else {
        ++lptr;
    }
    set_desc(&dma_info->dt_virt_addr[dma_idx]->descriptor[lptr], ep_addr,
             kmem_info->bus_addr + kmem_offset, size, lptr, is_read);
    dma_info->last_ptr[dma_idx] = lptr;
    ++dma_info->num_pending[dma_idx];

    return 0;
}

int intel_fpga_pcie_dma_send(struct dev_bookkeep *dev_bk, unsigned long uarg)
{
    struct timeval start_tv, end_tv;
    uint32_t timeout;
    bool send_transfer[2];
    bool has_pending[2];
    uint8_t lptr[2];
    bool lptr_wrap[2];
    int i;
    struct dma_info *dma_info;
    void *__iomem dma_ctrl_bar_base;

    timeout = DMA_TIMEOUT;
    dma_info = &dev_bk->dma_info;
    dma_ctrl_bar_base = dev_bk->bar[DMA_CTRL_BAR].base_addr;

    for (i=0; i<2; ++i) {
        send_transfer[i] = (uarg >> i) & 0x1UL;
        has_pending[i] = dma_info->num_pending[i] > 0;
        lptr[i] = dma_info->last_ptr[i];
        lptr_wrap[i] = dma_info->last_ptr_wrap[i];

        if (send_transfer[i]) {
            dma_info->last_ptr_wrap[i] = false;
            dma_info->num_pending[i] = 0;
        }
    }

    if (unlikely(!send_transfer[0] && !send_transfer[1])) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("must initiate DMA read or DMA write.");
        return -EINVAL;
    }

    if (unlikely(!(send_transfer[0] && has_pending[0]) &&
                 !(send_transfer[1] && has_pending[1]))) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("no DMA queued for either read "
                                       "or write.");
        return -ENODATA;
    }

    do_gettimeofday(&start_tv);

    for (i=0; i<2; ++i) {
        if (!send_transfer[i]) continue;

        dma_start(dev_bk, lptr[i], lptr_wrap[i], i==DMA_RD_IDX ? true : false);
    }

#if (INTERRUPT_ENABLED)
    for (i=0; i<2; ++i) {
        if (!send_transfer[i]) continue;

        timeout = wait_for_completion_timeout( 
            &dma_info->transaction[i], msecs_to_jiffies(10000));
    }
#else
    while (true) {
        if ((!send_transfer[0] || dma_info->dt_virt_addr[0]->header.status[lptr[0]]) &&
            (!send_transfer[1] || dma_info->dt_virt_addr[1]->header.status[lptr[1]])) {
            break;
        }

        if (timeout == 0) {
            break;
        }

        --timeout;
        cpu_relax();
    }
#endif

#if (DMA_VERSION_MAJOR == 4) 
    // This version of DMA does not require consecutive descriptors
    // to be used - can simply start back at
    dev_bk->dma_info.last_ptr[DMA_RD_IDX] = 127;
    dev_bk->dma_info.last_ptr[DMA_WR_IDX] = 127;
#endif

    do_gettimeofday(&end_tv);
    dev_bk->dma_info.timer = (end_tv.tv_sec - start_tv.tv_sec)*1000000 +
                             end_tv.tv_usec - start_tv.tv_usec;
    if (timeout == 0) {
        INTEL_FPGA_PCIE_WARN("DMA operation timed out.");
        return -ETIME;
    }

    return 0;
}

/**
 * intel_fpga_pcie_dma_probe() - Initialize DMA controller and driver with
 *                               non-recurring information necessary for
 *                               DMA operation.
 *
 * @dev_bk: Pointer to the device bookkeeping structure. The structure
 *          contains DMA information structure, such as descriptor
 *          tables or number of pending/completed DMA descriptors.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
int intel_fpga_pcie_dma_probe(struct dev_bookkeep *dev_bk)
{
    int retval;

    // Set DMA mask to limit the address range allocated for DMA by kernel.
#if (DMA_VERSION_MAJOR == 1)
    if (dma_set_mask_and_coherent(&dev_bk->dev->dev, DMA_BIT_MASK(32))) {
        // TXS uses 32-bit addressing by default in the example design.
        INTEL_FPGA_PCIE_ERR("couldn't use 32-bit mapping for DMA.");
        retval = -EADDRNOTAVAIL;
        goto failed_dma_mask;
    } else {
        INTEL_FPGA_PCIE_DEBUG("using a 32-bit mapping for DMA.");
    }
#elif (DMA_VERSION_MAJOR == 4)
    if (dma_set_mask_and_coherent(&dev_bk->dev->dev, DMA_BIT_MASK(64))) {
        INTEL_FPGA_PCIE_WARN("couldn't use 64-bit mapping for DMA.");
        if (dma_set_mask_and_coherent(&dev_bk->dev->dev, DMA_BIT_MASK(32))) {
            INTEL_FPGA_PCIE_ERR("couldn't use 32-bit mapping for DMA.");
            retval = -EADDRNOTAVAIL;
            goto failed_dma_mask;
        } else {
            INTEL_FPGA_PCIE_DEBUG("using a 32-bit mapping for DMA.");
        }
    } else {
        INTEL_FPGA_PCIE_DEBUG("using a 64-bit mapping for DMA.");
    }
#endif

    //Create read and write descriptor tables to be used in DMAs.
    dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX] =
        dma_alloc_coherent(&dev_bk->dev->dev,
                           sizeof(*(dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX])),
                           &dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX],
                           GFP_KERNEL);
    if (!dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX]) {
        INTEL_FPGA_PCIE_ERR("couldn't create read descriptor table.");
        retval = -ENOMEM;
        goto failed_rd_dt;
    }

    dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX] =
        dma_alloc_coherent(&dev_bk->dev->dev,
                           sizeof(*(dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX])),
                           &dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX],
                           GFP_KERNEL);
    if (!dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]) {
        INTEL_FPGA_PCIE_ERR("couldn't create write descriptor table.");
        retval = -ENOMEM;
        goto failed_wr_dt;
    }

    retval = intel_fpga_pcie_interrupt_probe(dev_bk);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't enable interrupts");
        goto failed_interrupt_ena;
    }

    // Allow device to generate upstream requests.
    pci_set_master(dev_bk->dev);

    // Initialize version-specific DMA information
    dma_init(dev_bk);

    up(&dev_bk->sem);
    return 0;

failed_wr_dt:
    dma_free_coherent(&dev_bk->dev->dev,
                      sizeof(*(dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX])),
                      dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX],
                      dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX]);
failed_rd_dt:
    intel_fpga_pcie_interrupt_remove(dev_bk);
failed_interrupt_ena:
failed_dma_mask:
    return retval;
}

void intel_fpga_pcie_dma_remove(struct dev_bookkeep *dev_bk)
{
    pci_clear_master(dev_bk->dev);
    dma_free_coherent(&dev_bk->dev->dev,
                      sizeof(*(dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX])),
                      dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX],
                      dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX]);
    dma_free_coherent(&dev_bk->dev->dev,
                      sizeof(*(dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX])),
                      dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX],
                      dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX]);
    intel_fpga_pcie_interrupt_remove(dev_bk);
}

static void set_desc(struct desc *desc, uint64_t ep_addr, dma_addr_t rp_addr,
                     uint32_t byte_len, uint32_t id, bool is_read)
{
    uint32_t ctrl;
    INTEL_FPGA_PCIE_VERBOSE_DEBUG("Setting %s desc %u with len %u rp %llx "
                                  " ep %llx\n", is_read ? "read" : "write",
                                  id, byte_len, rp_addr, ep_addr);
    if (is_read) {
        desc->src_addr = ep_addr;
        desc->dst_addr = (uint64_t) rp_addr;
    } else {
        desc->src_addr = (uint64_t) rp_addr;
        desc->dst_addr = ep_addr;
    }
    ctrl = byte_len/4;
#if (DMA_VERSION_MAJOR == 1)
    ctrl |= id << 18;
#elif (DMA_VERSION_MAJOR == 4)
    ctrl |= id << 24;
#endif
    desc->ctrl = cpu_to_le32(ctrl);
    desc->reserved[0] = cpu_to_le32(0x0);
    desc->reserved[1] = cpu_to_le32(0x0);
    desc->reserved[2] = cpu_to_le32(0x0);
}

/******************************************************************************
 * Version-specific DMA routines
 *****************************************************************************/
#if (DMA_VERSION_MAJOR == 1)
inline void set_rc_desc_base(void * __iomem dma_ctrl_bar_base,
                             uint64_t addr, bool is_read);
inline void set_ep_desc_base(void * __iomem dma_ctrl_bar_base,
                             uint64_t addr, bool is_read);
inline void set_lptr(void * __iomem dma_ctrl_bar_base,
                     uint8_t lptr, bool is_read);
inline uint8_t get_lptr(void * __iomem dma_ctrl_bar_base, bool is_read);

static void dma_init(struct dev_bookkeep *dev_bk)
{
    void *__iomem dma_ctrl_bar_base;
    // Let FPGA know where to fetch descriptor table + EP FIFO address.
    dma_ctrl_bar_base = dev_bk->bar[DMA_CTRL_BAR].base_addr;
    set_rc_desc_base(dma_ctrl_bar_base,
                     dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX], true);
    set_rc_desc_base(dma_ctrl_bar_base,
                     dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX], false);
    set_ep_desc_base(dma_ctrl_bar_base, RDESC_FIFO_ADDR, true);
    set_ep_desc_base(dma_ctrl_bar_base, WDESC_FIFO_ADDR, false);

    // Set last pointers in dev bookkeeper.
    dev_bk->dma_info.last_ptr[DMA_RD_IDX] = get_lptr(dma_ctrl_bar_base, true);
    dev_bk->dma_info.last_ptr[DMA_WR_IDX] = get_lptr(dma_ctrl_bar_base, false);
    mb();
}

static void dma_start(struct dev_bookkeep *dev_bk, uint8_t lptr,
                      bool lptr_wrap, bool is_read)
{
    void *__iomem dma_ctrl_bar_base;
    dma_ctrl_bar_base = dev_bk->bar[DMA_CTRL_BAR].base_addr;

    dev_bk->dma_info.dt_virt_addr[is_read ? DMA_RD_IDX : DMA_WR_IDX]->
        header.status[lptr] = 0;
    if (lptr_wrap) {
        set_lptr(dma_ctrl_bar_base, 127, is_read);
        wmb();
    }
    set_lptr(dma_ctrl_bar_base, lptr, is_read);
    wmb();
}

inline void set_rc_desc_base(void * __iomem dma_ctrl_bar_base,
                             uint64_t addr, bool is_read)
{
    iowrite32(cpu_to_le32((addr) >> 32),
              dma_ctrl_bar_base + RC_DESC_BASE_H_REG_OFFSET +
              (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                         DMA_TO_DEVICE_REG_OFFSET));
    iowrite32(cpu_to_le32(addr & 0xFFFFFFFFULL),
              dma_ctrl_bar_base + RC_DESC_BASE_L_REG_OFFSET +
              (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                         DMA_TO_DEVICE_REG_OFFSET));
}

inline void set_ep_desc_base(void * __iomem dma_ctrl_bar_base,
                             uint64_t addr, bool is_read)
{
    iowrite32(cpu_to_le32((addr) >> 32),
              dma_ctrl_bar_base + EP_FIFO_BASE_H_REG_OFFSET +
              (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                         DMA_TO_DEVICE_REG_OFFSET));
    iowrite32(cpu_to_le32(addr & 0xFFFFFFFFULL),
              dma_ctrl_bar_base + EP_FIFO_BASE_L_REG_OFFSET +
              (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                         DMA_TO_DEVICE_REG_OFFSET));
}

inline void set_lptr(void * __iomem dma_ctrl_bar_base,
                     uint8_t lptr, bool is_read)
{
    iowrite32(cpu_to_le32(lptr),
              dma_ctrl_bar_base + LPTR_REG_OFFSET +
              (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                         DMA_TO_DEVICE_REG_OFFSET));
}

inline uint8_t get_lptr(void * __iomem dma_ctrl_bar_base, bool is_read)
{
    return ioread8(dma_ctrl_bar_base + LPTR_REG_OFFSET +
                   (is_read ? DMA_FROM_DEVICE_REG_OFFSET :
                              DMA_TO_DEVICE_REG_OFFSET));
}
/* End if DMA_VERSION_MAJOR == 1 */
#elif (DMA_VERSION_MAJOR == 4)
static void set_immediate_desc(struct desc *desc, uint64_t addr, 
                               uint32_t data, uint32_t id);

static void dma_init(struct dev_bookkeep *dev_bk)
{
    dev_bk->dma_info.last_ptr[DMA_RD_IDX] = 127;
    dev_bk->dma_info.last_ptr[DMA_WR_IDX] = 127;
}

static void dma_start(struct dev_bookkeep *dev_bk, uint8_t lptr,
                      bool lptr_wrap, bool is_read)
{
    void *__iomem dt_fetch_queue_addr;
    struct desc dt_fetch_desc = {0};
    int i;
    uint32_t ctrl;

    if (lptr_wrap) {
        INTEL_FPGA_PCIE_ERR("DMA Version 4.0 should not wrap around for descriptors.");
        return;
    }
    
#if (INTERRUPT_ENABLED)
    // Done status bit is cleared by the interrupt
    if (is_read) {

        set_immediate_desc( // Set status bit
            &dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX]->descriptor[lptr+1],
            dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX] + 4*lptr,
            0x1ULL, 
            255
        );

        set_immediate_desc( // Send interrupt
            &dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX]->descriptor[lptr+2], 
            dev_bk->dma_info.interrupt_addr, 
            dev_bk->dma_info.interrupt_data, 
            253
        );
    } else {
        // Need to fetch status and interrupt desc into different destination.
        set_desc(
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+1],
            WRITE_DESC_PRIO_OFFSET,
            dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX] +
            (uintptr_t)(512 + (lptr+3)*32), 32, lptr+1, false
        );
        set_desc(
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+2],
            WRITE_DESC_PRIO_OFFSET,
            dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX] +
            (uintptr_t)(512 + (lptr+4)*32), 32, lptr+2, false
        );
        set_immediate_desc( // Set status bit
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+3],
            dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX] + 4*lptr,
            0x1ULL, 
            255
        );
        set_immediate_desc( // Send interrupt
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+4], 
            dev_bk->dma_info.interrupt_addr, 
            dev_bk->dma_info.interrupt_data,
            253
        );
    }
#else
    // Clear done status flag.
    dev_bk->dma_info.dt_virt_addr[is_read ? DMA_RD_IDX : DMA_WR_IDX]->
        header.status[lptr] = 0;
    if (is_read) {
        set_immediate_desc( // Set status bit
            &dev_bk->dma_info.dt_virt_addr[DMA_RD_IDX]->descriptor[lptr+1],
            dev_bk->dma_info.dt_bus_addr[DMA_RD_IDX] + 4*lptr,
            0x1ULL, 
            255
        );
    } else {
        // Need to fetch status desc into different destination.
        set_desc(
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+1],
            WRITE_DESC_PRIO_OFFSET,
            dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX] +
            (uintptr_t)(512 + (lptr+2)*32), 32, lptr+1, false
        );
        set_immediate_desc( // Set status bit
            &dev_bk->dma_info.dt_virt_addr[DMA_WR_IDX]->descriptor[lptr+2],
            dev_bk->dma_info.dt_bus_addr[DMA_WR_IDX] + 4*lptr,
            0x1ULL, 
            255
        );
    }
#endif
    // Fetch DT from RP into descriptor FIFO queues.
    dt_fetch_desc.src_addr = dev_bk->dma_info.dt_bus_addr[is_read ? DMA_RD_IDX : DMA_WR_IDX] + 512;
    dt_fetch_desc.dst_addr = is_read ? WRITE_DESC_NORM_OFFSET : READ_DESC_NORM_OFFSET;

    /*
      One extra descriptor is required to be fetched. Two if using interrupts.
      For reads (Host <-- FPGA), the last descriptor sets the DMA done status.
      For writes (Host --> FPGA), the last descriptor fetches the status
        descriptor which then sets the DMA done status.
      When using interrupts, there is an additional descriptor that sends the 
      interrupt, handled in the same way as the above.
     */
#if (INTERRUPT_ENABLED)
    ctrl = (lptr+3) * 8;
#else
    ctrl = (lptr+2) * 8;
#endif

    ctrl |= 1 << 20;    // Single destination
    ctrl |= 0xFE << 24; // Special descriptor ID
    dt_fetch_desc.ctrl = cpu_to_le32(ctrl);

    dt_fetch_queue_addr = dev_bk->bar[DMA_CTRL_BAR].base_addr +
                          (uintptr_t)(is_read ? READ_DESC_PRIO_OFFSET :
                                                READ_DESC_NORM_OFFSET);

    for (i=0; i<4; ++i) {
        iowrite32(*((uint32_t *)(&dt_fetch_desc)+i), dt_fetch_queue_addr+i*4);
    }
    wmb();
    // Most significant DWord must be written last.
    iowrite32(*((uint32_t *)(&dt_fetch_desc)+4), dt_fetch_queue_addr+16);
    wmb();

    // This version of DMA does not require consecutive descriptors
    // to be used - can simply start back at
    dev_bk->dma_info.last_ptr[DMA_RD_IDX] = 127;
    dev_bk->dma_info.last_ptr[DMA_WR_IDX] = 127;
}

static void set_immediate_desc(struct desc *desc, uint64_t addr, 
                               uint32_t data, uint32_t id)
{
    uint32_t ctrl;

    desc->src_addr = data;      // The data to write to given address
    desc->dst_addr = addr;
    ctrl = 1;                   // 1 DW status
    ctrl |= 1 << 18;            // Immediate access
    ctrl |= id << 24;           // Status descriptor ID
    desc->ctrl = cpu_to_le32(ctrl);
    desc->reserved[0] = cpu_to_le32(0x0);
    desc->reserved[1] = cpu_to_le32(0x0);
    desc->reserved[2] = cpu_to_le32(0x0);
}
#endif /* End if DMA_VERSION_MAJOR == 4 */

/******************************************************************************
 * Interrupt Completion Specific Routines
 *****************************************************************************/
#if (INTERRUPT_ENABLED)
static irqreturn_t transaction_complete(int irq, void * dev_bk);
// static irqreturn_t transaction_end_handler(int irq, void * dev_bk);
// static DECLARE_WORK(transaction_end, transaction_end_handler);

#if (INTERRUPT_TYPE == 1)
static int pull_interrupt_data(struct dev_bookkeep *dev_bk)
{
    int retval;
    int msi_capability;
    uint32_t msi_header, temp_addr;
    uint32_t interrupt_data;
    uint64_t interrupt_addr;


    // Determine if MSI is enabled
    msi_capability = pci_find_capability(dev_bk->dev, PCI_CAP_ID_MSI);
    if (!msi_capability) {
        INTEL_FPGA_PCIE_ERR("couldn't find associated msi capability structure");
        retval = -EOPNOTSUPP;
        goto failed_msi_init;
    }

    retval = pci_read_config_dword(dev_bk->dev, 
        msi_capability, &msi_header);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("error reading msi header from device config space");
        goto failed_msi_init;
    }

    // Determine address used to trigger MSI interrupt
    // Read lower address register
    retval = pci_read_config_dword(dev_bk->dev, 
        msi_capability + 0x4, &temp_addr);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("error reading msi lower address from device config space");
        goto failed_msi_init;
    }
    interrupt_addr = temp_addr;
    // Read upper address register, if present
    if ((msi_header >> 0x10) & 0x0080) { // 64 bit address capable field
        // Read upper address register
        retval = pci_read_config_dword(dev_bk->dev, 
            msi_capability + 0x8, &temp_addr);
        if (retval) {
            INTEL_FPGA_PCIE_ERR("error reading msi upper address from device config space");
            goto failed_msi_init;
        }
        interrupt_addr |= temp_addr << 0x10;
    }

    // Determine data used to trigger MSI interrupt
    if ((msi_header >> 0x10) & 0x0080) { // 64-bit address enabled
        if ((msi_header >> 0x10) & 0x0200) { // Extended message enable
            retval = pci_read_config_dword(dev_bk->dev, 
                msi_capability + 0xc, &interrupt_data);
        } else {
            retval = pci_read_config_word(dev_bk->dev, 
                msi_capability + 0xc, (uint16_t *) &interrupt_data);
        }
    } else {
        if ((msi_header >> 0x10) & 0x0200) { // Extended message enable
            retval = pci_read_config_dword(dev_bk->dev, 
                msi_capability + 0x8, &interrupt_data);
        } else {
            retval = pci_read_config_word(dev_bk->dev, 
                msi_capability + 0x8, (uint16_t *) &interrupt_data);
        }
    }
    if (retval) {
        INTEL_FPGA_PCIE_ERR("error reading msi data from device config space");
        intel_fpga_pcie_interrupt_remove(dev_bk);
        goto failed_msi_init;
    }

    dev_bk->dma_info.msi_capability = msi_capability;
    dev_bk->dma_info.interrupt_addr = interrupt_addr;
    dev_bk->dma_info.interrupt_data = interrupt_data;


    return 0;

failed_msi_init:
    return retval;
}

#elif (INTERRUPT_TYPE == 2)
static int pull_interrupt_data(struct dev_bookkeep *dev_bk)
{
    int retval;
    int msi_capability;
    uint32_t table_register, table_offset, table_BAR;
    void * __iomem BAR_addr;
    uint32_t interrupt_data;
    uint64_t interrupt_addr;


    // Determine if-X MSI is enabled
    msi_capability = pci_find_capability(dev_bk->dev, PCI_CAP_ID_MSIX);
    if (!msi_capability) {
        retval = -EOPNOTSUPP;
        INTEL_FPGA_PCIE_ERR("couldn't find associated msi-x capability structure");
        goto failed_msi_init;
    }

    // Get table offset / table_BIR register
    retval = pci_read_config_dword(dev_bk->dev, 
        msi_capability + 0x4, &table_register);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("error reading msi header from device config space");
        goto failed_msi_init;
    }
    table_offset = table_register & 0xFFFFFFF8; // everything but the last three
    table_BAR = table_register & 0x7; // get bar number from bir
    BAR_addr = dev_bk->bar[table_BAR].base_addr;

    interrupt_addr = ioread32(BAR_addr + table_offset);
    interrupt_addr |= ioread32(BAR_addr + table_offset + 0x4) << 0x10;
    interrupt_data = ioread32(BAR_addr + table_offset + 0x8);

    dev_bk->dma_info.msi_capability = msi_capability;
    dev_bk->dma_info.interrupt_addr = interrupt_addr;
    dev_bk->dma_info.interrupt_data = interrupt_data;

    return 0;

failed_msi_init:
    return retval;
}
    
#else
static int pull_interrupt_data(struct dev_bookkeep *dev_bk)
{
    return 0;
}

#endif

static int intel_fpga_pcie_interrupt_probe(struct dev_bookkeep *dev_bk)
{
    int retval;
    unsigned int irq = 0;

    // Allocate irq vectors and register interrupt handler
    retval = pci_alloc_irq_vectors(dev_bk->dev, 1, 1, PCI_IRQ_ALL_TYPES);
    if (retval <= 0) {
       INTEL_FPGA_PCIE_ERR("couldn't allocate irq vectors");

       goto failed_alloc_irq;
    }
    irq = pci_irq_vector(dev_bk->dev, 0);
    if (irq < 0) {
       INTEL_FPGA_PCIE_ERR("couldn't get associated irq number");
       retval = irq;
       goto failed_request_irq;
    }

    retval = request_irq(irq, transaction_complete, 0, "Altera FPGA", dev_bk);
    if (retval) {
       INTEL_FPGA_PCIE_ERR("couldn't register interrupt %d", -retval);
       goto failed_request_irq;
    }

    dev_bk->dma_info.irq = irq;

    // Transaction completions initialization
    init_completion(&dev_bk->dma_info.transaction[DMA_RD_IDX]);
    init_completion(&dev_bk->dma_info.transaction[DMA_WR_IDX]);

    retval = pull_interrupt_data(dev_bk);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't read necessary data from capability structure");
        goto failed_msi_init;
    }

    return 0;

failed_msi_init:
    free_irq(dev_bk->dma_info.irq, dev_bk);
failed_request_irq:
    pci_free_irq_vectors(dev_bk->dev);
failed_alloc_irq:
    return retval;
}

static void intel_fpga_pcie_interrupt_remove(struct dev_bookkeep *dev_bk)
{
    free_irq(dev_bk->dma_info.irq, dev_bk);
    pci_free_irq_vectors(dev_bk->dev);
}

static irqreturn_t transaction_complete(int irq, void *dev_bk) 
{
    irqreturn_t retval = IRQ_NONE;
    int i;
    struct dma_info * dma_info;
    dma_info = &((struct dev_bookkeep *) dev_bk)->dma_info;

    // If the status bit is set, then we know that the operation finished
    for (i = 0; i < 2; ++i) {
        if (dma_info->dt_virt_addr[i]->header.status[dma_info->last_ptr[i]]) {
            // Set status to zero to ensure completion is only incremented once
            dma_info->dt_virt_addr[i]->header.status[dma_info->last_ptr[i]] = 0;
            complete(&dma_info->transaction[i]);
            retval = IRQ_HANDLED;
        }
    }
    return retval;
}

#else /* End if INTERRUPT_ENABLED */

static int intel_fpga_pcie_interrupt_probe(struct dev_bookkeep *dev_bk)
{
    return 0;
}

static void intel_fpga_pcie_interrupt_remove(struct dev_bookkeep *dev_bk)
{
    return;
}

#endif

#else /* Else ifndef DMA_SUPPORTED */

int intel_fpga_pcie_dma_queue(struct dev_bookkeep *dev_bk, unsigned long uarg)
{
    INTEL_FPGA_PCIE_DEBUG("DMA is not supported by the device.");
    return -EOPNOTSUPP;
}

int intel_fpga_pcie_dma_send(struct dev_bookkeep *dev_bk, unsigned long uarg)
{
    INTEL_FPGA_PCIE_DEBUG("DMA is not supported by the device.");
    return -EOPNOTSUPP;
}

int intel_fpga_pcie_dma_probe(struct dev_bookkeep *dev_bk)
{
    return 0;
}

void intel_fpga_pcie_dma_remove(struct dev_bookkeep *dev_bk)
{
    return;
}

#endif /* End of ifndef DMA_SUPPORTED */
