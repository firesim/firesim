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
#include "intel_fpga_pcie_setup.h"

/******************************************************************************
 * Static function prototypes
 *****************************************************************************/
static long sel_dev(struct chr_dev_bookkeep *dev_bk, unsigned long new_bdf);
static long get_dev(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned int __user *user_addr);
static long sel_bar(struct chr_dev_bookkeep *dev_bk, unsigned long new_bar);
static long get_bar(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned int __user *user_addr);
static long checked_cfg_access(struct pci_dev *dev, unsigned long uarg);
static long set_kmem_size(struct dev_bookkeep *dev_bk, unsigned long size);
static long get_ktimer(struct dev_bookkeep *dev_bk,
                       unsigned int __user *user_addr);


/******************************************************************************
 * Device and I/O control function
 *****************************************************************************/
/**
 * intel_fpga_pcie_unlocked_ioctl() - Responds to the system call ioctl(2).
 * @filp: Pointer to file struct.
 * @cmd:  The command value corresponding to some desired action.
 * @uarg: Optional argument passed to the system call. This could actually
 *        be data or it could be a pointer to some structure which has been
 *        casted.
 *
 * Return: 0 on success, negative error code on failure.
 */
long intel_fpga_pcie_unlocked_ioctl(struct file *filp, unsigned int cmd,
                                    unsigned long uarg)
{
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;
    long retval = 0;
    int numvfs;

    if (unlikely(_IOC_TYPE(cmd) != INTEL_FPGA_PCIE_IOCTL_MAGIC)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("ioctl called with wrong magic "
                                      "number: %d", _IOC_TYPE(cmd));
        return -ENOTTY;
    }

    if (unlikely(_IOC_NR(cmd) > INTEL_FPGA_PCIE_IOCTL_MAXNR)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("ioctl called with wrong "
                                      "command number: %d", _IOC_NR(cmd));
        return -ENOTTY;
    }

    /*
     * The direction is a bitmask, and VERIFY_WRITE catches R/W transfers.
     * `Type' is user-oriented, while access_ok is kernel-oriented, so the
     * concept of "read" and "write" is reversed.
     */
    if (_IOC_DIR(cmd) & _IOC_READ) {
        // Note: VERIFY_WRITE is a superset of VERIFY_READ
        retval = !access_ok((void __user *)uarg, _IOC_SIZE(cmd));
    } else if (_IOC_DIR(cmd) & _IOC_WRITE) {
        retval = !access_ok((void __user *)uarg, _IOC_SIZE(cmd));
    }
    if (unlikely(retval)) {
        INTEL_FPGA_PCIE_DEBUG("ioctl access violation.");
        return -EFAULT;
    }

    // Retrieve bookkeeping information.
    chr_dev_bk = filp->private_data;
    dev_bk = chr_dev_bk->dev_bk;

    // Determine access type.
    switch (cmd) {
    case INTEL_FPGA_PCIE_IOCTL_CHR_SEL_DEV:
        retval = sel_dev(chr_dev_bk, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_CHR_GET_DEV:
        retval = get_dev(chr_dev_bk, (unsigned int __user *)uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_CHR_SEL_BAR:
        retval = sel_bar(chr_dev_bk, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_CHR_GET_BAR:
        retval = get_bar(chr_dev_bk, (unsigned int __user *)uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_CHR_USE_CMD:
        chr_dev_bk->use_cmd = (bool) uarg;
        break;
    case INTEL_FPGA_PCIE_IOCTL_CFG_ACCESS:
        retval = checked_cfg_access(dev_bk->dev, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_SRIOV_NUMVFS:
        numvfs = intel_fpga_pcie_sriov_configure(dev_bk->dev, (int)uarg);

        // SR-IOV configure returns number of VFs enabled.
        if (numvfs == (int)uarg)
            retval = 0;
        else if (numvfs > 0)
            retval = -ENODEV;
        else
            retval = (long)numvfs;
        break;
    case INTEL_FPGA_PCIE_IOCTL_SET_KMEM_SIZE:
        retval = set_kmem_size(dev_bk, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_DMA_QUEUE:
        retval = intel_fpga_pcie_dma_queue(dev_bk, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_DMA_SEND:
        retval = intel_fpga_pcie_dma_send(dev_bk, uarg);
        break;
    case INTEL_FPGA_PCIE_IOCTL_GET_KTIMER:
        retval = get_ktimer(dev_bk, (unsigned int __user *)uarg);
        break;
    default:
        retval = -ENOTTY;
    }

    return retval;
}


/******************************************************************************
 * Helper functions
 *****************************************************************************/
/**
 * sel_dev() - Switches the selected device to a potentially different
 *             device.
 *
 * @chr_dev_bk: Structure containing information about the current
 *              character file handle.
 * @new_bdf:    The BDF of the potentially different device to select.
 *
 * Searches through the list of devices probed by this driver. If a device
 * with matching BDF is found, it is selected to be accessed by the
 * particular file handle. During this process, both old and new devices'
 * bookkeeping structures are locked to ensure consistency.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long sel_dev(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned long new_bdf)
{
    struct dev_bookkeep *new_dev_bk, *old_dev_bk;
    unsigned long old_bdf;

    // Search for device with new BDF number.
    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "global lock.");
        return -ERESTARTSYS;
    }
    new_dev_bk = radix_tree_lookup(&global_bk.dev_tree, new_bdf);
    mutex_unlock(&global_bk.lock);

    if (new_dev_bk == NULL) {
        INTEL_FPGA_PCIE_DEBUG("could not find device with BDF %04lx.", new_bdf);
        return -ENODEV;
    }

    old_dev_bk = chr_dev_bk->dev_bk;
    old_bdf = old_dev_bk->bdf;

    if (old_bdf == new_bdf) {
        // 'Switched' to the same BDF! No bookkeeping update required; exit.
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("didn't have to change device.");
        return 0;
    } else if (likely(old_bdf < new_bdf)) {
        /*
         * Two semaphores required - always obtain lower-numbered
         * BDF's lock first to avoid deadlock. The old BDF should
         * typically be lower since default device selected always
         * has the lowest BDF, and it is not expected that the
         * selected device is changed more than once.
         */
        if (unlikely(down_interruptible(&old_dev_bk->sem))) {
            INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                                  "old device semaphore.");
            return -ERESTARTSYS;
        }
        if (unlikely(down_interruptible(&new_dev_bk->sem))) {
            INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                                  "new device semaphore.");
            up(&old_dev_bk->sem);
            return -ERESTARTSYS;
        }
    } else {
        if (unlikely(down_interruptible(&new_dev_bk->sem))) {
            INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                                  "new device semaphore.");
            return -ERESTARTSYS;
        }
        if (unlikely(down_interruptible(&old_dev_bk->sem))) {
            up(&new_dev_bk->sem);
            INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                                  "old device semaphore.");
            return -ERESTARTSYS;
        }
    }

    // Update counters
    --old_dev_bk->chr_open_cnt;
    ++new_dev_bk->chr_open_cnt;

    // Release order doesn't matter.
    up(&old_dev_bk->sem);
    up(&new_dev_bk->sem);


    chr_dev_bk->dev_bk = new_dev_bk;
    return 0;
}

/**
 * get_dev() - Copies the currently selected device's BDF to the user.
 *
 * @chr_dev_bk: Structure containing information about the current
 *              character file handle.
 * @user_addr:  Address to an unsigned int in user-space.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long get_dev(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned int __user *user_addr)
{
    struct dev_bookkeep *dev_bk;
    unsigned int bdf;

    dev_bk = chr_dev_bk->dev_bk;
    bdf = (unsigned int) dev_bk->bdf;

    if (copy_to_user(user_addr, &bdf, sizeof(bdf))) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy BDF information to user.");
        return -EFAULT;
    }

    return 0;
}


/**
 * sel_bar() - Switches the selected device to a potentially different
 *             device.
 *
 * @chr_dev_bk: Structure containing information about the current
 *              character file handle.
 * @new_bar:    The potentially different BAR to select.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long sel_bar(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned long new_bar)
{
    if (new_bar >= 6) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested a BAR number "
                                      "which is too large.");
        return -EINVAL;
    }

    // Disallow changing of BAR if command structure is being used for comm.
    if (chr_dev_bk->use_cmd) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("command structure is in use; "
                                      "selected BAR will not be changed.");
        return -EPERM;
    }

    // Ensure valid BAR is selected.
    if (chr_dev_bk->dev_bk->bar[new_bar].base_addr == NULL) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested a BAR number "
                                      "which is unallocated.");
        return -EINVAL;
    }

    chr_dev_bk->cur_bar_num = new_bar;

    return 0;
}

/**
 * get_bar() - Copies the currently selected device BAR to the user.
 *
 * @chr_dev_bk: Structure containing information about the current
 *              character file handle.
 * @user_addr:  Address to an unsigned int in user-space.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long get_bar(struct chr_dev_bookkeep *chr_dev_bk,
                    unsigned int __user *user_addr)
{
    unsigned int bar;

    bar = chr_dev_bk->cur_bar_num;

    if (copy_to_user(user_addr, &bar, sizeof(bar))) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy BAR information to user.");
        return -EFAULT;
    }

    return 0;
}

/**
 * checked_cfg_access() - Do a configuration space access after checking
 *                        access validity.
 * @dev:  PCI device to access.
 * @uarg: Pointer to the IOCTL command structure located
 *        within the user-space.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long checked_cfg_access(struct pci_dev *dev, unsigned long uarg)
{
    struct intel_fpga_pcie_arg karg;
    uint8_t temp[4];
    int cfg_addr;
    uint32_t count;
    int retval;
    
    if(! access_ok(uarg, sizeof(uarg))){
        INTEL_FPGA_PCIE_DEBUG("uarg is not ok to access");
        return -EFAULT;
    }

    if (copy_from_user(&karg, (void __user *)uarg, sizeof(karg))) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy arg from user.");
        return -EFAULT;
    }
    
    if ((karg.size < 0) || karg.size > 10000000) { //check if transfer size is < 0 or > 10MB. Coverity
        INTEL_FPGA_PCIE_DEBUG("Invalid transfer size.");
        return -EFAULT;
    }
    
    count = karg.size;

    // Copy write data from user if writing.
    if (!karg.is_read && copy_from_user(&temp, karg.user_addr, count)) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy data from user.");
        return -EFAULT;
    }

    if (unlikely(karg.ep_addr > (uint64_t)INT_MAX)) {
        INTEL_FPGA_PCIE_DEBUG("address is out of range.");
        return -EINVAL;
    } else {
        cfg_addr = (int)karg.ep_addr;
    }

    /*
     * Check alignment - this also ensures that the access does not cross
     * out of bounds.
     */
    if (unlikely((cfg_addr % count) != 0)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("config access is misaligned.");
        return -EINVAL;
    }
    if (unlikely(cfg_addr >= 0xC00)) {
        /*
         * Config space extends to 0xFFF according to PCIe specification
         * but the PCIe IP requires >= 0xC00 to be implemented in user space
         * of the FPGA. Without user implementation of cfg access response,
         * the FPGA device may hang.
         */
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("address is out of config space.");
        return -EINVAL;
    }

    // Do access
    if (karg.is_read) {
        switch (count) {
        case 1:
            retval = pci_read_config_byte(dev, cfg_addr, temp);
            break;
        case 2:
            retval = pci_read_config_word(dev, cfg_addr, (uint16_t *)temp);
            break;
        case 4:
            retval = pci_read_config_dword(dev, cfg_addr, (uint32_t *)temp);
            break;
        default:
            INTEL_FPGA_PCIE_VERBOSE_DEBUG("access size is invalid.");
            return -EINVAL;
        }
    } else {
        switch (count) {
        case 1:
            retval = pci_write_config_byte(dev, cfg_addr, temp[0]);
            break;
        case 2:
            retval = pci_write_config_word(dev, cfg_addr, *(uint16_t *)temp);
            break;
        case 4:
            retval = pci_write_config_dword(dev, cfg_addr, *(uint32_t *)temp);
            break;
        default:
            INTEL_FPGA_PCIE_VERBOSE_DEBUG("access size is invalid.");
            return -EINVAL;
        }
    }

    // Copy read data to user if reading.
    if (karg.is_read && copy_to_user(karg.user_addr, &temp, count)) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy data to user.");
        return -EFAULT;
    }

    return retval;
}

/**
 * set_kmem_size() - If size is non-zero, obtains DMA-capable kernel memory
 *                   for use as a bounce buffer or for scratch memory.
 *
 * @dev_bk:     Pointer to the device bookkeeping structure. The structure
 *              is accessed to retrieve information about existing kernel
 *              memories allocated for the same purpose, and allocated
 *              memory is accessed through this structure.
 * @size:       The number of bytes to allocate, up to 1 MiB. Passing in
 *              a size of 0 will free the currently allocated memory.
 *
 * Obtains DMA-capable kernel memory for use as a bounce buffer or for
 * scratch memory. This memory is accessible through dev_bk structure.
 * Only one such memory can be allocated per device at any time.
 *
 * For simplicity, memory resizing is not supported - previously
 * allocated memory will be freed and replaced by a new memory region.
 * If the new memory region cannot be obtained, the old memory region
 * will be retained.
 *
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long set_kmem_size(struct dev_bookkeep *dev_bk, unsigned long size)
{
    long retval = 0;
    struct kmem_info old_info;

    if (unlikely(size > (1*1024*1024))) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("requested size is too large.");
        return -EINVAL;
    }

    if (unlikely(down_interruptible(&dev_bk->sem))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "device semaphore.");
        return -ERESTARTSYS;
    }

    old_info.size       = dev_bk->kmem_info.size;
    old_info.virt_addr  = dev_bk->kmem_info.virt_addr;
    old_info.bus_addr   = dev_bk->kmem_info.bus_addr;

    // Get new memory region first if requested.
    if (size > 0) {
        dev_bk->kmem_info.virt_addr =
            dma_alloc_coherent(&dev_bk->dev->dev, size,
                                &dev_bk->kmem_info.bus_addr, GFP_KERNEL);
        if (!dev_bk->kmem_info.virt_addr) {
            INTEL_FPGA_PCIE_DEBUG("couldn't obtain kernel buffer.");
            // Restore old memory region.
            dev_bk->kmem_info.virt_addr = old_info.virt_addr;
            dev_bk->kmem_info.bus_addr = old_info.bus_addr;
            retval = -ENOMEM;
        } else {
            dev_bk->kmem_info.size = size;
        }
    } else {
        dev_bk->kmem_info.size = 0;
        dev_bk->kmem_info.virt_addr = NULL;
        dev_bk->kmem_info.bus_addr = 0;
    }

    // If getting the new memory region was successful and
    // there was another allocated region, deallocate the previous
    // memory region.
    if ((retval == 0) && ((old_info.size > 0) || (size == 0))) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("freeing previously allocated memory.");
        dma_free_coherent(&dev_bk->dev->dev, old_info.size,
                          old_info.virt_addr,
                          old_info.bus_addr);
    }

    up(&dev_bk->sem);

    return retval;
}

/**
 * get_ktimer() - Copies the currently selected device BAR to the user.
 *
 * @dev_bk:     Pointer to the device bookkeeping structure. The structure
 *              is accessed to retrieve the timer which was recorded for
 *              the last DMA transaction.
 * @user_addr:  Address to an unsigned int in user-space.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static long get_ktimer(struct dev_bookkeep *dev_bk,
                       unsigned int __user *user_addr)
{
    unsigned int timer;

    timer = dev_bk->dma_info.timer;

    if (copy_to_user(user_addr, &timer, sizeof(timer))) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy kernel timer information to user.");
        return -EFAULT;
    }

    return 0;
}
