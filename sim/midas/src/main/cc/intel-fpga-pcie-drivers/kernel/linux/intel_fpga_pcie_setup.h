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

#ifndef INTEL_FPGA_PCIE_SETUP_H
#define INTEL_FPGA_PCIE_SETUP_H

#include <linux/cdev.h>
#include <linux/dma-mapping.h>
#include <linux/fs.h>
#include <linux/ioctl.h>
#include <linux/init.h>
#include <linux/interrupt.h>
#include <linux/jiffies.h>
#include <linux/kernel.h>
#include <linux/ktime.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/mutex.h>
#include <linux/pci.h>
#include <linux/types.h>
#include <linux/radix-tree.h>
#include <linux/completion.h>
#include <linux/sched.h>
#include <linux/uaccess.h>
#include <linux/version.h>
#include <linux/workqueue.h>

#include "../intel_fpga_pcie_ip_params.h"


#define INTEL_FPGA_VENDOR_ID 0x1172
#define INTEL_FPGA_DEVICE_ID 0x09C4
#define INTEL_FPGA_PCIE_DRIVER_NAME "intel_fpga_pcie_drv"


#ifndef PCI_DEVID
#define PCI_DEVID(bus, devfn)  ((((u16)(bus)) << 8) | (devfn))
#endif


/**
 * struct bar_info - Contains information about a single BAR.
 *
 * @base_addr:          The base address of the BAR.
 * @len:                Length of the BAR region.
 * @is_prefetchable:    Indicates whether the BAR is prefetchable.
 * @is_hprxm:           Indicates whether the BAR is connected to an
 *                      HPRXM within the end-point.
 */
struct bar_info {
    void * __iomem base_addr;
    ssize_t len;
    bool is_prefetchable;
    bool is_hprxm;
};

/**
 * struct kmem_info - Contains information about a DMA-capable, physically
 *                    contiguous kernel memory region allocated to a device.
 *
 * @virt_addr:      Kernel virtual address.
 * @bus_addr:       Bus address.
 * @size:           Memory size in number of bytes.
 */
struct kmem_info {
    void *virt_addr;
    dma_addr_t bus_addr;
    size_t size;
};

/**
 * struct dma_info - Contains information used for DMA operation, including
 *                   pending descriptors. Each field except for the timer
 *                   exists for _each_ direction of DMA operation.
 *
 * @timer:          Timer, in microseconds. The timer measures the time elapsed
 *                  from the start of DMA operation to the end of DMA operation.
 * @dt_virt_addr:   Kernel virtual addresses for read and write descriptor
 *                  tables. Used to access the DTs from the processor.
 * @dt_bus_addr:    Bus (PCIe) addresses for read and write descriptor
 *                  tables. Used to access the DTs from the end point.
 * @last_ptr:       Value indicating the last descriptor number, AKA last
 *                  pointer, sent to the end point for DMA.
 * @num_pending:    Number of pending descriptors, where 'pending' indicates
 *                  that a descriptor has been set up but DMA on the
 *                  descriptor has not been initiated.
 * @last_ptr_wrap:  Indicates that the last pointer has wrapped around.
 */
struct dma_info {
    unsigned int timer;

    // Data to for MSI completion of DMA
    int msi_capability;         // Offset of MSI capability
    uint64_t interrupt_addr;    // Address to trigger MSI interrupt
    uint32_t interrupt_data;    // Data to be sent with interrupt packet
    unsigned int irq;
    struct completion transaction[2];

    // DMA descriptor tables, one for each direction
    struct desc_table *dt_virt_addr[2];
    dma_addr_t dt_bus_addr[2];
    uint8_t last_ptr[2];
    uint8_t num_pending[2];
    bool last_ptr_wrap[2];
};

/**
 * struct global_bookkeep - Global bookkeeping structure. Mostly used to
 *                          track character device and give access to
 *                          correct device structure.
 *
 * @cdev:           Character device handle.
 * @chr_class:      Character device class.
 * @chr_major:      Character device major number.
 * @chr_minor:      Character device minor number.
 * @dev_tree:       Radix tree root. Used to track probed devices.
 * @lock:           Synchronizes accesses to this structure. Cannot be
 *                  used in interrupt context.
 */
struct global_bookkeep {
    struct cdev cdev;
    struct class *chr_class;
    int chr_major;
    int chr_minor;
    struct radix_tree_root dev_tree;
    struct mutex lock;
};


/**
 * struct dev_bookkeep - Bookkeeping structure per device.
 *
 * @dev:            PCI device handle.
 * @bdf:            Device's bus-device-function.
 * @bar:            Device's BARs
 * @kmem_info:      Contains information on this device's kernel
 *                  memory.
 * @chr_open_cnt:   Number of character device handles opened with
 *                  this device selected.
 * @sem:            Synchronizes accesses to this structure. Can be used in
 *                  interrupt context. Currently, only @chr_open_cnt requires
 *                  synchronization as other fields are written once.
 */
struct dev_bookkeep {
    struct pci_dev *dev;
    uint16_t bdf;
    struct bar_info bar[6];
    struct kmem_info kmem_info;
    struct dma_info dma_info;
    int chr_open_cnt;
    struct semaphore sem;
};


/**
 * struct chr_dev_bookkeep - Bookkeeping structure per character device
 *                           handle.
 *
 * @dev_bk:         Pointer to the device bookkeeping structure for the
 *                  currently selected device.
 * @use_cmd:        Set to true if character device is using a command
 *                  structure to convey access BAR/location.
 * @cur_bar_num:    If @use_cmd is false, indicates the current BAR being
 *                  accessed.
 */
struct chr_dev_bookkeep {
    struct dev_bookkeep *dev_bk;
    bool use_cmd;
    unsigned int cur_bar_num;
};

int intel_fpga_pcie_probe(struct pci_dev *dev, const struct pci_device_id *id);
void intel_fpga_pcie_remove(struct pci_dev *dev);
int intel_fpga_pcie_sriov_configure(struct pci_dev *dev, int numvfs);


extern struct global_bookkeep global_bk;


#endif /* INTEL_FPGA_PCIE_SETUP_H */
