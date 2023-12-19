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
#include "intel_fpga_pcie_chr.h"
#include "intel_fpga_pcie_dma.h"
#include "intel_fpga_pcie_ioctl.h"
#include "intel_fpga_pcie_setup.h"

/*
 * Define global bookkeeper as a global variable in .bss section.
 * Compared to dynamically allocating the memory for the global structure
 * at run-time, this creates a compile-time address. Hopefully, this allows
 * for the compiler to do address access optimizations, if possible.
 * In theory, all accesses to memory should be the same either way.
 * This makes super minimal difference, I believe.
 */
struct global_bookkeep global_bk __read_mostly;


// Determine BAR type from auto-generated IP parameters.
static const int bar_types[6] = {BAR0_TYPE, BAR1_TYPE, BAR2_TYPE,
                                 BAR3_TYPE, BAR4_TYPE, BAR5_TYPE};

/******************************************************************************
 * Static function prototypes
 *****************************************************************************/
static int __init intel_fpga_pcie_init(void);
static void __exit intel_fpga_pcie_exit(void);

static int map_bars_default(struct pci_dev *dev);
static void unmap_bars(struct pci_dev *dev);



/******************************************************************************
 * PCIe driver functions
 *****************************************************************************/
/**
 * intel_fpga_pcie_probe() - The kernel will automatically call this function
 *                           if a PCI device is supported by this driver
 *                           (according to &intel_fpga_pcie_id_table).
 * @dev: PCI device to be probed.
 * @id:  Currently unused as all devices with matching VID are handled
 *       identically.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
int intel_fpga_pcie_probe(struct pci_dev *dev, const struct pci_device_id *id)
{
    int retval;
    struct dev_bookkeep *dev_bk;
    uint16_t bdf = PCI_DEVID(dev->bus->number, dev->devfn);

    INTEL_FPGA_PCIE_VERBOSE_DEBUG("VID = 0x%x, DevID = 0x%x, class = 0x%x, "
                                  "bus:dev.func = %02x:%02x.%02x bdf = %04x",
                                  dev->vendor, dev->device, dev->class,
                                  dev->bus->number, PCI_SLOT(dev->devfn),
                                  PCI_FUNC(dev->devfn), bdf);

    // Allocate per-device bookkeeper and set fields
    dev_bk = kzalloc(sizeof(*dev_bk), GFP_KERNEL);
    if (dev_bk == NULL) {
        INTEL_FPGA_PCIE_ERR("couldn't create device bookkeeper for "
                            "device with BDF: %04x.", bdf);
        retval = -ENOMEM;
        goto failed_devbk_alloc;
    }
    sema_init(&dev_bk->sem, 1);
    dev_bk->chr_open_cnt = 0;
    dev_bk->dev = dev;
    dev_bk->bdf = bdf;


    // Save the bookkeeper in (private) driver data
    pci_set_drvdata(dev, dev_bk);


    // Enable PCIe device
    retval = pci_enable_device(dev);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't enable PCIe device.");
        goto failed_pcie_enable;
    }


    /*
     * Mark BAR regions of memory as being used/owned by this driver.
     * Then, map the BARs into kernel virtual memory.
     */
    retval = pci_request_regions(dev, INTEL_FPGA_PCIE_DRIVER_NAME);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't request PCIe resources.");
        goto failed_req_region;
    }
    /*
     * Warning: For RXM, using write-combining will likely result in
     * failure - WC may combine multiple adjacent writes into one packet
     * that crosses DWORD boundary!
     */
    retval = map_bars_default(dev);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't map BAR regions.");
        goto failed_map;
    }

    retval = intel_fpga_pcie_dma_probe(dev_bk);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't initialize DMA controller.");
        goto failed_dma_init;
    }

    /*
     * // Read special config space to determine parameterization of the IP
     * // (e.g. custom user-specific extended capability).
     *
     * custom_usec = pci_find_capability(dev, PCI_CAP_ID_CUSTOM);
     * if (custom_usec != BAD) {
     *     retval = pci_read_config_word(dev, custom_usec + 0x1C, &val16);
     *     if (val16 == 0x1UL) {
     *         // Have HW info
     *     }
     * }
     */


    // Add this device to the global bookkeeper
    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "global lock.");
        retval = -ERESTARTSYS;
        goto failed_get_gbk;
    }
    retval = radix_tree_insert(&global_bk.dev_tree, (unsigned long) bdf,
                               dev_bk);
    mutex_unlock(&global_bk.lock);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't insert device bookkeeper into "
                            "device tree.");
        goto failed_devtree_insert;
    }

    return retval;

failed_devtree_insert:
failed_get_gbk:
    intel_fpga_pcie_dma_remove(dev_bk);
failed_dma_init:
    unmap_bars(dev);
failed_map:
    pci_release_regions(dev);
failed_req_region:
    pci_disable_device(dev);
failed_pcie_enable:
    pci_set_drvdata(dev, NULL);
    kfree(dev_bk);
failed_devbk_alloc:
    return retval;
}

/**
 * intel_fpga_pcie_remove() - When pci_unregister_driver() is called for this
 *                            PCIe driver, the kernel will invoke this function
 *                            for all devices using the driver. This undos the
 *                            work of intel_fpga_pcie_probe().
 * @dev: PCI device to be removed.
 *
 * Return: Nothing.
 */
void intel_fpga_pcie_remove(struct pci_dev *dev)
{
    struct dev_bookkeep *dev_bk;
    bool release_lock = true;
    dev_bk = pci_get_drvdata(dev);

    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_WARN("global driver lock acquisition has been "
                             "interrupted during driver removal; "
                             "internal structures may be corrupted!");
        release_lock = false;
    }

    dev_bk = radix_tree_delete(&global_bk.dev_tree, dev_bk->bdf);

    if (release_lock) {
        mutex_unlock(&global_bk.lock);
    }

    if (dev_bk == NULL) {
        INTEL_FPGA_PCIE_WARN("could not find device with matching BDF "
                             "%04x in device tree during removal!",
                             dev_bk->bdf);
    }

    if (dev_bk->kmem_info.size) {
        dma_free_coherent(&dev_bk->dev->dev, dev_bk->kmem_info.size,
                          dev_bk->kmem_info.virt_addr,
                          dev_bk->kmem_info.bus_addr);
    }

    intel_fpga_pcie_dma_remove(dev_bk);
    unmap_bars(dev);
    pci_release_regions(dev);
    pci_disable_device(dev);
    pci_set_drvdata(dev, NULL);
    kfree(dev_bk);
};

/**
 * intel_fpga_pcie_sriov_configure() - Enables or disables VFs.
 * @dev:    PCI device with VFs.
 * @numvfs: Number of VFs to enable. If 0, disables all VFs.
 *
 * Return: 0 if disabling all VFs, number of VFs enabled if enabling VFs,
 *         and negative error code otherwise.
 */
int intel_fpga_pcie_sriov_configure(struct pci_dev *dev, int numvfs)
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(2, 6, 30)
    int result;
    if (numvfs > 0) {
        result = pci_enable_sriov(dev, numvfs);

        if (result != numvfs)
            INTEL_FPGA_PCIE_DEBUG("could not enable as many VFs as requested.");

        if (result == 0)
            return numvfs;
        else
            return result;
    } else if (numvfs == 0) {
        pci_disable_sriov(dev);
        return 0;
    }
    return -EINVAL;
#else
    INTEL_FPGA_PCIE_WARN("SR-IOV specification has not been defined yet "
                         "at time of kernel release.");
    return -EINVAL;
#endif
}



/******************************************************************************
 * PCIe Structures
 *****************************************************************************/
/**
 * struct pci_device_id - Lists devices compatible with this driver. This
 *                        driver is intended to be compatible with any device
 *                        with Intel FPGA's vendor ID.
 */
struct pci_device_id intel_fpga_pcie_id_table[] = {
    { PCI_DEVICE(INTEL_FPGA_VENDOR_ID, PCI_ANY_ID) },
    { 0 },
};

/**
 * struct pci_driver - Declares this driver as a PCI driver.
 */
struct pci_driver intel_fpga_pcie_driver = {
    .name = INTEL_FPGA_PCIE_DRIVER_NAME,
    .id_table = intel_fpga_pcie_id_table,
    .probe    = intel_fpga_pcie_probe,
    .remove   = intel_fpga_pcie_remove,
#if LINUX_VERSION_CODE >= KERNEL_VERSION(3, 8, 3)
    .sriov_configure = intel_fpga_pcie_sriov_configure,
#endif
};



/******************************************************************************
 * Registration to the kernel
 *****************************************************************************/
/**
 * intel_fpga_pcie_init() - Registers the driver so that the kernel knows its
 *                          existence.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static int __init intel_fpga_pcie_init(void)
{
    int retval = 0;

    /*
     * Initialize bookkeeper global to all PCIe devices, which will track
     * information such as the number of devices plugged.
     */
    INIT_RADIX_TREE(&global_bk.dev_tree, GFP_KERNEL);
    mutex_init(&global_bk.lock);

    retval = intel_fpga_pcie_chr_init();
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't register character device.");
        goto failed_chr_reg;
    }

    retval = pci_register_driver(&intel_fpga_pcie_driver);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't register driver.");
        goto failed_pci_reg;
    }

    return retval;


    /*
     * If the initialization failed, undo everything that was done and
     * return the error.
     */
failed_pci_reg:
    intel_fpga_pcie_chr_exit();
failed_chr_reg:
    return retval;
}
module_init(intel_fpga_pcie_init);

/**
 * intel_fpga_pcie_exit() - This function unregisters the driver from the
 *                          kernel so that it cannot be called anymore.
 *
 * Return: Nothing
 */
static void __exit intel_fpga_pcie_exit(void)
{
    pci_unregister_driver(&intel_fpga_pcie_driver);
    intel_fpga_pcie_chr_exit();
}
module_exit(intel_fpga_pcie_exit);



/******************************************************************************
 * Helper functions
 *****************************************************************************/
/**
 * map_bars() - Maps all existing BAR regions with default settings (cacheable
 *              memory if prefetchable; nocache otherwise).
 * @dev: PCI device whose BARs will be mapped.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static int map_bars_default(struct pci_dev *dev)
{
    int i;
    unsigned long start, len, flags;
    struct dev_bookkeep *dev_bk;
    dev_bk = pci_get_drvdata(dev);


    // Initialize to NULL pointers
    for (i = 0; i < 6; ++i) {
        dev_bk->bar[i].base_addr = NULL;
        dev_bk->bar[i].is_hprxm = (bar_types[i] == HPRXM) || (bar_types[i] == BAM);
        dev_bk->bar[i].is_prefetchable = false;
    }

    for (i = 0; i < 6; ++i) {
        len   = pci_resource_len(dev, i);
        if (len != 0) {
            dev_bk->bar[i].len = (ssize_t) len;
            start = pci_resource_start(dev, i);
            flags = pci_resource_flags(dev, i);

            if (flags & IORESOURCE_IO) {
                dev_bk->bar[i].base_addr = ioport_map(start, len);
            } else {
                dev_bk->bar[i].base_addr = ioremap_nocache(start, len);

                if (flags & IORESOURCE_PREFETCH)
                    dev_bk->bar[i].is_prefetchable = true;
            }
            if (!dev_bk->bar[i].base_addr) {
                INTEL_FPGA_PCIE_ERR("failed at mapping BAR %i.", i);
                break;
            }
        }
    }

    if (i != 6) {
        unmap_bars(dev);
        return -ENOMEM;
    }

    return 0;
}

/**
 * unmap_bars() - Unmaps BAR regions in use by the device. Assumes proper
 *                synchronization of the fields are handled elsewhere or
 *                that the synchronization is implicit.
 * @dev: PCI device whose BARs will be unmapped.
 *
 * Return: Nothing
 */
static void unmap_bars(struct pci_dev *dev)
{
    int i;
    struct dev_bookkeep *dev_bk;
    unsigned long flags;

    dev_bk = pci_get_drvdata(dev);

    for (i = 0; i < 6; ++i) {
        if (dev_bk->bar[i].base_addr) {
            flags = pci_resource_flags(dev, i);
            if (flags & IORESOURCE_IO) {
                // IO ports use different unmapping.
                ioport_unmap(dev_bk->bar[i].base_addr);
            } else {
                iounmap(dev_bk->bar[i].base_addr);
            }
            dev_bk->bar[i].base_addr = NULL;
            dev_bk->bar[i].len = 0;
        }
    }
}


// Metadata information
MODULE_AUTHOR           ("Intel Corp");
MODULE_DESCRIPTION      ("Driver for Intel(R) FPGA PCIe IP");
MODULE_VERSION          ("1.0");
MODULE_SUPPORTED_DEVICE ("Intel(R) Stratix 10 FPGA");
MODULE_DEVICE_TABLE     (pci, intel_fpga_pcie_id_table);
MODULE_LICENSE          ("Dual BSD/GPL");

