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
#include "intel_fpga_pcie_ioctl.h"
#include "intel_fpga_pcie_setup.h"

/******************************************************************************
 * Static function prototypes
 *****************************************************************************/
static int chr_open(struct inode *inode, struct file *filp);
static int chr_release(struct inode *inode, struct file *filp);
static ssize_t chr_read(struct file *filp, char __user *buf,
                        size_t count, loff_t *offp);
static ssize_t chr_write(struct file *filp, const char __user *buf,
                         size_t count, loff_t *offp);
static loff_t chr_llseek(struct file *filp, loff_t off, int whence);
static int chr_mmap(struct file *filp, struct vm_area_struct *vma);
static void chr_vma_close(struct vm_area_struct *vma);
static ssize_t chr_access(struct file *filp, const char __user *buf,
                          size_t count, loff_t *offp, bool is_read);
static uintptr_t get_address(struct dev_bookkeep *dev_bk,
                             unsigned int bar_num,
                             uint64_t offset, size_t *count);
static void hprxm_access(uintptr_t addr, uint8_t *data,
                         uint16_t count, bool is_read);
static void hprxm_access_left_justified(uintptr_t addr, uint8_t *data,
                                        uint16_t count, bool is_read);
static void hprxm_access_right_justified(uintptr_t addr, uint8_t *data,
                                         uint16_t count, bool is_read);
static void hprxm_access_single(uintptr_t addr, uint8_t *data,
                                uint16_t count, bool is_read);
static void rxm_access(uintptr_t addr, uint8_t *data, uint16_t count,
                       bool is_read, bool is_prefetchable);
static void rxm_access_single(uintptr_t addr, uint8_t *data,
                              uint16_t count, bool is_read);
static void rxm_prefetchable_single_read(uintptr_t addr, uint8_t *data,
                                         uint16_t count);
static inline uint16_t round_down_to_po2_mask(uint16_t num);
static inline uint16_t round_up_to_po2(uint16_t num);
static void custom_ioread256_x86(uint8_t *data, void __iomem *addr);
static void custom_iowrite256_x86(uint8_t *data, void __iomem *addr);
static void custom_ioread128_x86(uint8_t *data, void __iomem *addr);
static void custom_iowrite128_x86(uint8_t *data, void __iomem *addr);
static void custom_ioread64(uint8_t *data, void __iomem *addr);
static void custom_iowrite64(uint8_t *data, void __iomem *addr);


/******************************************************************************
 * File operation functions
 *****************************************************************************/
const struct file_operations intel_fpga_pcie_fops = {
    .owner          = THIS_MODULE,
    .open           = chr_open,
    .release        = chr_release,
    .read           = chr_read,
    .write          = chr_write,
    .llseek         = chr_llseek,
    .unlocked_ioctl = intel_fpga_pcie_unlocked_ioctl,
    .mmap           = chr_mmap,
};

const struct vm_operations_struct intel_fpga_vm_ops = {
    .close  = chr_vma_close,
};

/**
 * chr_open() - Responds to the system call open(2). Opens the
 *              lowest-numbered device.
 * @inode: Unused - the actual file has no real significance.
 * @filp:  A pointer to a file structure which is unique per each open call.
 *
 * Also responds to openat(2). If the driver has not matches any devices,
 * the function fails. If there are multiple devices, the device with the
 * lowest BDF is selected.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static int chr_open(struct inode *inode, struct file *filp)
{
    int i;
    int result = 0;
    void *a_dev_bk;
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;
    unsigned int num_dev_bks;

    // Look for the device with lowest BDF and select it as default.
    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "global lock.");
        return -ERESTARTSYS;
    }
    num_dev_bks = radix_tree_gang_lookup(&global_bk.dev_tree, &a_dev_bk,
                                         0, 1);
    mutex_unlock(&global_bk.lock);

    if (unlikely(num_dev_bks < 1)) {
        INTEL_FPGA_PCIE_DEBUG("no Intel FPGA PCIe device has been found.");
        return -ENXIO;
    }


    // Create a bookkeeping structure for this particular open file.
    chr_dev_bk = kzalloc(sizeof(*chr_dev_bk), GFP_KERNEL);
    if (chr_dev_bk == NULL) {
        INTEL_FPGA_PCIE_DEBUG("couldn't create character device bookkeeper.");
        return -ENOMEM;
    }
    dev_bk = a_dev_bk;
    chr_dev_bk->dev_bk = dev_bk;
    filp->private_data = chr_dev_bk;

    /*
     * Even if no BARs exist, address checks during the actual
     * access will flag invalid access and fail gracefully.
     */
    chr_dev_bk->use_cmd = false;

    // Set the current BAR number to the lowest valid BAR.
    chr_dev_bk->cur_bar_num = 0;    // Initial value.
    for (i=0; i<6; ++i) {
        if (dev_bk->bar[i].base_addr != NULL) {
            chr_dev_bk->cur_bar_num = i;
            break;
        }
    }


    // Increase device open count.
    if (unlikely(down_interruptible(&dev_bk->sem))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "device semaphore.");
        return -ERESTARTSYS;
    }
    ++dev_bk->chr_open_cnt;
    INTEL_FPGA_PCIE_VERBOSE_DEBUG("opened new handle to device with BDF %04x. "
                                  "Total handle open count is %d.",
                                  dev_bk->bdf, dev_bk->chr_open_cnt);
    up(&dev_bk->sem);

    return result;
}

/**
 * chr_release() - Responds to the system call close(2). Clean up character
 *                 device file structure.
 * @inode: Unused.
 * @filp:  Pointer to file struct.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
static int chr_release(struct inode *inode, struct file *filp)
{
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;
    chr_dev_bk = filp->private_data;
    dev_bk = chr_dev_bk->dev_bk;

    if (unlikely(down_interruptible(&dev_bk->sem))) {
        INTEL_FPGA_PCIE_DEBUG("interrupted while attempting to obtain "
                              "device semaphore.");
        return -ERESTARTSYS;
    }
    --dev_bk->chr_open_cnt;
    INTEL_FPGA_PCIE_VERBOSE_DEBUG("closed handle to device with BDF %04x. "
                                  "Total handle open count is %d.",
                                  dev_bk->bdf, dev_bk->chr_open_cnt);
    up(&dev_bk->sem);

    kfree(chr_dev_bk);

    return 0;
}

/**
 * chr_read() - Responds to the system calls read(2) and pread(2).
 *
 * System calls readv(2) and preadv(2) indirectly invoke this function as well
 * since the vector versions that normally respond to them are not defined.
 *
 * Return: Number of bytes read if at least 1 byte accessed. Negative error
 *         code otherwise.
 */
static ssize_t chr_read(struct file *filp, char __user *buf,
                        size_t count, loff_t *offp)
{
    return chr_access(filp, (const char __user *)buf, count, offp, true);
}

/**
 * chr_write() - Responds to the system calls write(2) and pwrite(2).
 *
 * System calls writev(2) and pwritev(2) indirectly invoke this function as
 * well since the vector versions that normally respond to them are
 * not defined.
 *
 * Return: Number of bytes written if at least 1 byte accessed. Negative error
 *         code otherwise.
 */
static ssize_t chr_write(struct file *filp, const char __user *buf,
                         size_t count, loff_t *offp)
{
    return chr_access(filp, buf, count, offp, false);
}

/**
 * chr_llseek() - Responds to the system call lseek(2) and its related calls.
 *
 * This function modifies or returns the offset within a BAR region.
 */
static loff_t chr_llseek(struct file *filp, loff_t off, int whence)
{
    loff_t newpos;
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;

    switch(whence) {
    case 0: // SEEK_SET
        newpos = off;
        break;
    case 1: // SEEK_CUR
        newpos = filp->f_pos + off;
        break;
    case 2: // SEEK_END
        /*
         * Return the end of this BAR region. This can be useful if the user
         * wants to know the end of a BAR region. However, any access at
         * the returned location will _most likely_ be invalid - the only
         * exception is if a different BAR region is adjacent to the current
         * BAR region.
         */
        chr_dev_bk = filp->private_data;
        dev_bk = chr_dev_bk->dev_bk;

        newpos = dev_bk->bar[chr_dev_bk->cur_bar_num].len + off;
        break;
    default: // can't happen
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("invalid seek method %d attempted.",
                                      whence);
        return -EINVAL;
    }

    if (newpos < 0) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("offset underflow due to "
                                      "invalid input.");
        return -EINVAL;
    }

    filp->f_pos = newpos;
    return newpos;
}

static int chr_mmap(struct file *filp, struct vm_area_struct *vma)
{
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;
    unsigned long len, pfn;
    int ret;
    pgoff_t pgoff;

    len = vma->vm_end - vma->vm_start;
    pgoff = vma->vm_pgoff;

    chr_dev_bk = filp->private_data;
    dev_bk = chr_dev_bk->dev_bk;

    if ((len == 0) || (len+pgoff) > dev_bk->kmem_info.size) {
        return -EINVAL;
    }
    vma->vm_ops = &intel_fpga_vm_ops;
    vma->vm_flags |= VM_PFNMAP | VM_DONTCOPY | VM_DONTEXPAND;
    vma->vm_page_prot = pgprot_noncached(vma->vm_page_prot);
    vma->vm_private_data = dev_bk;

    pfn = __pa(dev_bk->kmem_info.virt_addr + (pgoff<<PAGE_SHIFT))>>PAGE_SHIFT;
    ret = remap_pfn_range(vma, vma->vm_start, pfn, len, vma->vm_page_prot);
    if (ret < 0) {
        INTEL_FPGA_PCIE_WARN("could not remap kernel buffer to user-space.");
        return -ENXIO;
    }

    return 0;
}

static void chr_vma_close(struct vm_area_struct *vma)
{
    // Do nothing.
}


/******************************************************************************
 * Initialization functions
 *****************************************************************************/
/**
 * intel_fpga_pcie_chr_init() - Populates sysfs entry with a new device class
 *                              and creates a character device.
 *
 * Return: 0 if successful, negative error code otherwise.
 */
int __init intel_fpga_pcie_chr_init(void)
{
    int retval;
    dev_t dev_id;
    struct device *dev;

    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_ERR("global driver lock acquisition has been "
                            "interrupted during driver initialization.");
        return -ERESTARTSYS;
    }

    // Create a class of devices; this also populates sysfs entries
    global_bk.chr_class = class_create(THIS_MODULE,
                                       INTEL_FPGA_PCIE_DRIVER_NAME);
    if (IS_ERR(global_bk.chr_class)) {
        retval = PTR_ERR(global_bk.chr_class);
        INTEL_FPGA_PCIE_ERR("couldn't create device class.");
        goto failed_class_create;
    }

    // Dynamically allocate chrdev region major number
    retval = alloc_chrdev_region(&dev_id, 0, 1, INTEL_FPGA_PCIE_DRIVER_NAME);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("couldn't register character device.");
        goto failed_alloc_chrdev;
    }
    global_bk.chr_major = MAJOR(dev_id);
    global_bk.chr_minor = MINOR(dev_id);

    /*
     * Initialize and add character device to the kernel, and associate
     * the correct file operations with this character device.
     */
    cdev_init(&global_bk.cdev, &intel_fpga_pcie_fops);
    global_bk.cdev.owner = THIS_MODULE;
    global_bk.cdev.ops = &intel_fpga_pcie_fops;

    // Connect the major/minor number to the character device
    retval = cdev_add(&global_bk.cdev, dev_id, 1);
    if (retval) {
        INTEL_FPGA_PCIE_ERR("failed at creating character device <%i,%i>.",
                            global_bk.chr_major, global_bk.chr_minor);
        goto failed_create_chrdev;
    }

    /*
     * Automatically load the character device to a /dev node.
     * This works by sending uevents to udev; the exact behavior
     * may be modified through /etc/udev/rules.d/.
     *
     * Alternatively, the character device could be loaded by the user using
     * the system call mknod.
     */
#if LINUX_VERSION_CODE < KERNEL_VERSION(2, 6, 26)
    dev = device_create(global_bk.chr_class, NULL, dev_id,
                        INTEL_FPGA_PCIE_DRIVER_NAME);
#else
    dev = device_create(global_bk.chr_class, NULL, dev_id, NULL,
                        INTEL_FPGA_PCIE_DRIVER_NAME);
#endif
    if (IS_ERR(dev)) {
        INTEL_FPGA_PCIE_ERR("failed at creating device under /dev/.");
        goto failed_create_dev;
    }

    mutex_unlock(&global_bk.lock);
    return retval;

failed_create_dev:
    cdev_del(&global_bk.cdev);
failed_create_chrdev:
    unregister_chrdev_region(dev_id, 1);
    global_bk.chr_major = 0;
    global_bk.chr_minor = 0;
failed_alloc_chrdev:
    class_destroy(global_bk.chr_class);
failed_class_create:
    global_bk.chr_class = NULL;
    mutex_unlock(&global_bk.lock);
    return retval;
}

/**
 * intel_fpga_pcie_chr_exit() - Undo everything done
 *                              by intel_fpga_pcie_chr_init().
 *
 * Return: Nothing
 */
void intel_fpga_pcie_chr_exit(void)
{
    bool release_lock = true;
    if (unlikely(mutex_lock_interruptible(&global_bk.lock))) {
        INTEL_FPGA_PCIE_WARN("global driver lock acquisition has been "
                             "interrupted during driver removal; "
                             "internal structures may be corrupted!");
        release_lock = false;
    }
    device_destroy(global_bk.chr_class, MKDEV(global_bk.chr_major,
                                              global_bk.chr_minor));
    cdev_del(&global_bk.cdev);
    unregister_chrdev_region(MKDEV(global_bk.chr_major,
                                   global_bk.chr_minor), 1);
    global_bk.chr_major = 0;
    global_bk.chr_minor = 0;
    class_destroy(global_bk.chr_class);
    global_bk.chr_class = NULL;

    if (release_lock) {
        mutex_unlock(&global_bk.lock);
    }
}


/******************************************************************************
 * Helper functions
 *****************************************************************************/
/**
 * chr_access() - Common character device access function.
 *
 * @filp:       Pointer to file struct.
 * @buf:        A pointer to a modifiable buffer if access is read, a pointer
 *              to a buffer if access is write. For read, the const-ness is
 *              casted away.
 * @count:      Number of bytes to be accessed. The function can access up to
 *              64 bytes per call.
 * @offp:       Byte offset from the BAR region base. This is only used
 *              if file offset mode of character device communication
 *              is selected.
 * @is_read:    Set to true if the access is a read. If this is true,
 *              buf must be a pointer to a modifiable buffer which has
 *              been type-casted to a constant buffer.
 *
 * All accesses are truncated to maximum of 64 bytes. Any access which goes
 * past the end of a BAR region is truncated to the end of the region.
 *
 * If the targeted BAR routes to an RXM, accesses are split into
 * multiple DWORD-aligned accesses. The first split boundary is at a natural
 * RXM/HPRXM access boundary so that subsequent accesses can take full
 * advantage of individual transfer boundaries.
 *
 * If the targeted BAR routes to an HPRXM, accesses smaller than a DWORD may
 * not be allowed as HPRXM does not allow byte-enables. Small reads are allowed
 * if the BAR is prefetchable. Other accesses are split into multiple
 * power-of-2-byte-aligned accesses. There are various other restrictions on
 * accessing HPRXM - refer to the comments in this function for details.
 *
 * Note that accesses to a BAM is handled identically to an access to an HPRXM,
 * even though the BAM is less restrictive. This is because there are limited
 * number of IO instructions which limit alignments/access sizes.
 *
 * Return: The number of bytes actually accessed.
 */
static ssize_t chr_access(struct file *filp, const char __user *buf,
                          size_t count, loff_t *offp, bool is_read)
{
    struct chr_dev_bookkeep *chr_dev_bk;
    struct dev_bookkeep *dev_bk;
    struct intel_fpga_pcie_cmd kcmd;

    /*
     * Align to 32B boundary so it can be used with 32B aligned transfers.
     * The extra 32B exist to allow source and destination addresses
     * to have the same 32B alignment as well as to allow prefetching.
     */
    uint8_t data[MAX_TRANSFER_SIZE + 32] __attribute__((aligned (32)));
    uint8_t *data_ptr;
    unsigned int bar_num;
    uint64_t offset;
    uintptr_t ep_addr;
    uintptr_t ep_addr_aligned, data_ptr_aligned;
    const char __user *user_addr;
    ssize_t bar_len;
    bool is_hprxm;
    bool is_prefetchable;


    if (count == 0) return 0;

    /*
     * Put a limit on the maximum size that can be transferred in one
     * system call. This is to avoid the use of kmalloc or multiple
     * copy_from/to_user calls.
     */
    if (count > MAX_TRANSFER_SIZE) {
        count = MAX_TRANSFER_SIZE;
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("access size reduced to %d.",
                                      MAX_TRANSFER_SIZE);
    }

    // Retrieve bookkeeping information.
    chr_dev_bk = filp->private_data;
    dev_bk = chr_dev_bk->dev_bk;

    if(! access_ok(buf, sizeof(buf))) {
        INTEL_FPGA_PCIE_DEBUG("buf is not ok to access");
        return -EFAULT;
    }

    // Determine target BAR, offset, and user addresses.
    if (chr_dev_bk->use_cmd) {
        if (copy_from_user(&kcmd, buf, sizeof(kcmd)) != 0) {
            INTEL_FPGA_PCIE_DEBUG("couldn't copy cmd from user.");
            return -EFAULT;
        } 
       
        if ((kcmd.bar_num < 0) || (kcmd.bar_num > 8)) {
            INTEL_FPGA_PCIE_DEBUG("Invalid BAR Number.");
            return -EFAULT;
        } 

        bar_num = kcmd.bar_num;
        offset = kcmd.bar_offset;
        user_addr = kcmd.user_addr;
        
    } else {
        bar_num = chr_dev_bk->cur_bar_num;
        offset = *offp;
        user_addr = buf;
    }

    // Validate access address, and potentially truncate.
    ep_addr = get_address(dev_bk, bar_num, offset, &count);
    if (unlikely((void *)ep_addr == NULL)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("invalid address selected.");
        return -EFAULT;
    }

    // Get address & BAR type
    bar_len = dev_bk->bar[bar_num].len;
    is_hprxm = dev_bk->bar[bar_num].is_hprxm;
    is_prefetchable = dev_bk->bar[bar_num].is_prefetchable;

    // Access validity check
    if (is_hprxm) {
        /*
         * For HPRXM, smallest granularity is 4B, unless reading
         * prefetchable region.
         */
        if (unlikely(!(is_read && is_prefetchable)
                     && ((ep_addr % 4) || (count % 4)))) {
            INTEL_FPGA_PCIE_VERBOSE_DEBUG("access granularity is smaller "
                                          "than a DWORD.");
            return -EINVAL;
        }
    } else {
        // For RXM, any access is possible using multiple transactions.
    }

    /*
     * Align data pointer to the same offset within a 4B or 32B frame
     * as the ep_addr. This allows source and destination addresses to be
     * aligned and thus allows use of aligned instructions.
     */
    data_ptr = data;
    if (is_hprxm) {
        data_ptr += ep_addr % 32;
    } else {
        data_ptr += ep_addr % 4;
    }

    //  If access is a write, copy over write data from user.
    if (!is_read && copy_from_user(data_ptr, user_addr, count)) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy data from user.");
        return -EFAULT;
    }

    // Do access
    if (is_hprxm) {
        if (is_read && is_prefetchable) {
            ep_addr_aligned = ep_addr & ~(uintptr_t)0x1f;
            data_ptr_aligned = (uintptr_t)data_ptr & ~(uintptr_t)0x1f;

            // For simplicity, just read extra
            hprxm_access_single(ep_addr_aligned,
                                (uint8_t *)data_ptr_aligned, 32, is_read);
            if (((ep_addr & 0x1f) + count) > 32) {
                hprxm_access_single(ep_addr_aligned + 32,
                                    (uint8_t *)data_ptr_aligned + 32,
                                    32, is_read);
            }
            if (((ep_addr&0x3f) + count) > 64) {
                hprxm_access_single(ep_addr_aligned + 64,
                                    (uint8_t *)data_ptr_aligned + 64,
                                    32, is_read);
            }
        } else {
            hprxm_access(ep_addr, data_ptr, (uint16_t)count, is_read);
        }
    } else {
        rxm_access(ep_addr, data_ptr, (uint16_t)count,
                   is_read, is_prefetchable);
    }

    // If access is a read, copy over read data to user.
    if (is_read && copy_to_user((char __user *) user_addr, data_ptr, count)) {
        INTEL_FPGA_PCIE_DEBUG("couldn't copy data to user.");
        return -EFAULT;
    }

    // If necessary, update the file offset.
    if (!chr_dev_bk->use_cmd) {
        *offp = *offp + count;
        if (*offp == bar_len) {
            *offp = 0;
        }
    }

    return count;
}

/**
 * get_address() - Returns the address targeted by BAR number and offset.
 *                 Modifies the count to truncate at the end of the BAR
 *                 region.
 *
 * @dev_bk:     Pointer to the device bookkeeping structure. The structure
 *              is accessed to retrieve information about the device's
 *              BARs' characteristics.
 * @bar_num:    BAR number to be targeted.
 * @offset:     Byte offset from the base of the BAR.
 * @count:      Pointer to the number of bytes to be accessed. The value
 *              at the pointer is updated with a smaller value if
 *              access is truncated.
 *
 * Based on the BAR number, offset, and access size, the address
 * to be accessed is returned. If the access goes past the end of the BAR
 * region, the access is truncated to the maximum allowed. If the offset
 * is outside the BAR region, the access is deemed invalid and a NULL is
 * returned.
 *
 * Return: NULL if desired access location is invalid. Otherwise,
 *         the address at the offset in a BAR region is returned.
 */
static uintptr_t get_address(struct dev_bookkeep *dev_bk,
                             unsigned int bar_num,
                             uint64_t offset, size_t *count)
{
    void __iomem *addr, __iomem *bar_base;
    ssize_t bar_len, remaining_region;

    bar_base = dev_bk->bar[bar_num].base_addr;
    if (unlikely(bar_base == NULL)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("invalid BAR selected.");
        return (uintptr_t) NULL;
    }

    bar_len = dev_bk->bar[bar_num].len;

    if (unlikely(offset >= bar_len)) {
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("offset %llu larger than size of "
                                      "BAR region %ld.", offset, bar_len);
        return (uintptr_t) NULL;
    }

    addr = bar_base + offset;

    remaining_region = bar_len - offset;
    if (unlikely(*count > remaining_region)) {
        // Truncate the count value.
        *count = remaining_region;
        INTEL_FPGA_PCIE_VERBOSE_DEBUG("truncated access to the end of BAR.");
    }

    return (uintptr_t) addr;
}

/**
 * hprxm_access() - Access a BAR which routes to an HPRXM.
 *
 * @addr:       EP address to access.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access. Must be a multiple of 4 bytes.
 * @is_read:    Set to true if the access is a read.
 *
 * Avalon-MM (and thus HPRXM) requires that the number of consecutive
 * bytes enabled must be a power of 2 and aligned to the same power of 2
 * address boundary.
 *
 * As such, an access which is not a power of 2 or improperly aligned must
 * be split into several smaller aligned power of 2 accesses. The splitting
 * consists of two main parts:
 *   1. Split the access such that each access is either left-justified
 *      or right-justified. Here, being left-justified means that the
 *      start address is aligned to a multiple the largest power of 2, where
 *      this power of 2 is also not greater than the number of bytes to access.
 *          Example 1 - reading 5DWs at address 4B (1DW):
 *              right-justified access: 3DW read at address 1DW
 *              left-justified access:  2DW read at address 4DW
 *                                      (needs alignment to a multiple of 4DW)
 *          Example 2 - reading 9DWs at address 8B (2DW):
 *              right-justified access: 6DW read at address 2DW
 *              left-justified access:  3DW read at address 8DW
 *                                      (needs alignment to a multiple of 8DW)
 *   2. For the left-justified half, start with largest power of 2 access
 *      aligned at the left and do successively smaller accesses.
 *      For right-justified half, do successively larger accesses such
 *      that the remaining access size does not have a remainder after
 *      division by an increasingly large power of 2 value.
 *      More details for each are given in hprxm_access_left_justified() and
 *      hprxm_access_right_justified() functions.
 *
 * Return: Nothing
 */
static void hprxm_access(uintptr_t addr, uint8_t *data,
                         uint16_t count, bool is_read)
{
    uint16_t start_addr, end_addr;
    uint16_t align_mask;
    uint16_t next_po2_div2;
    uint16_t offset;
    uint16_t diff;

    start_addr = addr % 32;
    end_addr = start_addr + count;

    /*
     * Determine the largest power of 2 _not greater_ than @count
     * and find a mask for it.
     */
    align_mask = round_down_to_po2_mask(count);

    // Determine if access is already justified or requires splitting.
    if ((start_addr & align_mask) == 0) {
        hprxm_access_left_justified(addr, data, count, is_read);
    } else if ((end_addr & align_mask) == 0) {
        hprxm_access_right_justified(addr, data, count, is_read);
    } else {
        /*
         * Calculate the split point.
         * This is done by repeatedly shifting the access location
         * by the largest power of 2 _less than_ the end address
         * until the access crosses address 0.
         */
        offset = 0;
        do {
            next_po2_div2 = round_up_to_po2(end_addr - offset);
            next_po2_div2 >>= 1;

            offset += next_po2_div2;
        } while (next_po2_div2 < (start_addr - offset + next_po2_div2));

        diff = offset - start_addr;
        hprxm_access_right_justified(addr, data, diff, is_read);
        hprxm_access_left_justified(addr + diff, data + diff,
                                    count - diff, is_read);
    }
}

/**
 * hprxm_access_left_justified() - Access a BAR which routes to an HPRXM
 *                                 where the access is left-justified.
 *
 * @addr:       EP address to access. @addr%32 must equal the largest power
 *              of 2 not greater than @count.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access. Must be a multiple of 4 bytes.
 * @is_read:    Set to true if the access is a read.
 *
 * The access is decomposed into a sum of power of 2s. The access begins
 * with the largest power of 2 at the starting address. Subsequent accesses
 * use successively smaller power of 2 terms from the end address of the
 * previous access.
 *
 * For example, if accessing 28B = (16B + 8B + 4B) at address 0:
 *   1. Access 16B at address 0.
 *   2. Access 8B at address 16.
 *   3. Access 4B at address 24.
 */
static void hprxm_access_left_justified(uintptr_t addr, uint8_t *data,
                                        uint16_t count, bool is_read)
{
    uint16_t po2 = round_up_to_po2(count);

    while (count > 0) {
        while (count >= po2) {
            hprxm_access_single(addr, data, po2, is_read);
            addr += po2;
            data += po2;
            count -= po2;
        }
        po2 >>= 1;
    }
}

/**
 * hprxm_access_right_justified() - Access a BAR which routes to an HPRXM
 *                                  where the access is right-justified.
 *
 * @addr:       EP address to access. (@addr + @count)%32 must equal
 *              the largest power of 2 not greater than @count.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access. Must be a multiple of 4 bytes.
 * @is_read:    Set to true if the access is a read.
 *
 * The access is decomposed into a sum of power of 2s. The access begins
 * with the smallest power of 2 at the starting address. Subsequent accesses
 * use successively larger power of 2 terms from the end address of the
 * previous access.
 *
 * For example, if accessing 28B = (16B + 8B + 4B) at address 4:
 *     1. Access 4B at address 4.
 *     2. Access 8B at address 8.
 *     3. Access 16B at address 16.
 */
static void hprxm_access_right_justified(uintptr_t addr, uint8_t *data,
                                         uint16_t count, bool is_read)
{
    uint16_t po2 = 2;
    uint16_t po2_div2;
    while (count > 0) {
        po2_div2 = po2/2;
        if (count % po2) {
            hprxm_access_single(addr, data, po2_div2, is_read);
            addr += po2_div2;
            data += po2_div2;
            count -= po2_div2;
        }
        po2 <<= 1;
    }
}


/**
 * hprxm_access_single() - Access a BAR which routes to an HPRXM. Access
 *                         alignment within a 32B frame and its size must be
 *                         a power of 2.
 *
 * @addr_:      EP address to access. (@addr_ % @count) must be 0.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access.
 *              Must be a power of 2 between 4 and 64 inclusive.
 * @is_read:    Set to true if the access is a read.
 *
 * 2^n bytes of data is accessed at some address, where n is 2 to 6 inclusive.
 * The address must be aligned at a multiple of the size within a 32B frame.
 */
static void hprxm_access_single(uintptr_t addr_, uint8_t *data,
                                uint16_t count, bool is_read)
{
    void __iomem *addr = (void __iomem *)addr_;
    switch (count) {
    case 64:
        if (is_read) {
            custom_ioread256_x86(data, addr);
            custom_ioread256_x86(data+32, addr+32);
        } else {
            custom_iowrite256_x86(data, addr);
            custom_iowrite256_x86(data+32, addr+32);
        }
        break;
    case 32:
        if (is_read) {
            custom_ioread256_x86(data, addr);
        } else {
            custom_iowrite256_x86(data, addr);
        }
        break;
    case 16:
        if (is_read) {
            custom_ioread128_x86(data, addr);
        } else {
            custom_iowrite128_x86(data, addr);
        }
        break;
    case 8:
        if (is_read) {
            custom_ioread64(data, addr);
        } else {
            custom_iowrite64(data, addr);
        }
        break;
    case 4:
        if (is_read) {
            *(uint32_t *)data = ioread32(addr);
        } else {
            iowrite32(*(uint32_t *)data, addr);
        }
        break;
    default:
        // No other sizes are valid.
        break;
    }
}

/**
 * rxm_access() - Access a BAR which routes to an RXM.
 *
 * @addr:       EP address to access.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access.
 * @is_read:    Set to true if the access is a read.
 *
 * The access is split into 3 stages:
 *     1. Access 1-3B such that the next byte to access is at a
 *        DWORD-aligned address.
 *     2. Access multiple 4Bs until less than 4B of data remain.
 *     3. Access remaining 1-3B of data.
 */
static void rxm_access(uintptr_t addr, uint8_t *data, uint16_t count,
                       bool is_read, bool is_prefetchable)
{
    uint16_t first_access_max;

    // Potentially do first access; align future accesses to a DWORD address.
    first_access_max = 4 - (addr % 4);
    if (first_access_max < count) {
        if (is_read && is_prefetchable) {
            rxm_prefetchable_single_read(addr, data, first_access_max);
        } else {
            rxm_access_single(addr, data, first_access_max, is_read);
        }

        data += first_access_max;
        addr += first_access_max;
        count -= first_access_max;
    }

    // Do intermediate accesses
    while (count >= 4) {
        rxm_access_single(addr, data, 4, is_read);
        addr += 4;
        data += 4;
        count -= 4;
    }

    // Potentially do last access
    if (count > 0) {
        if (is_read && is_prefetchable) {
            rxm_prefetchable_single_read(addr, data, count);
        } else {
            rxm_access_single(addr, data, count, is_read);
        }
    }
}

/**
 * rxm_access_single() - Access a BAR which routes to an RXM. The access must
 *                       be within a DWORD frame.
 *
 * @addr_:      EP address to access.
 * @data:       If access is a write, buffer containing data to write.
 *              If access is a read, buffer to save read data.
 * @count:      Number of bytes to access within a DWORD frame.
 * @is_read:    Set to true if the access is a read.
 *
 * 1-4 bytes of data is accessed at some address. This function assumes that
 * the access requested does not cross a DWORD frame. Specifically, @addr_/4
 * (integer division) and (@addr_+@count)/4 must be equal.
 */
static void rxm_access_single(uintptr_t addr_, uint8_t *data,
                              uint16_t count, bool is_read)
{
    void __iomem *addr = (void __iomem *)addr_;
    if (likely(count == 4)) {
        if (is_read) {
            *(uint32_t *)data = ioread32(addr);
        } else {
            iowrite32(*(uint32_t *)data, addr);
        }
    } else if (count == 1) {
        if (is_read) {
            data[0] = ioread8(addr);
        } else {
            iowrite8(data[0], addr);
        }
    } else if (count == 2) {
        if ((addr_ % 2) == 1) {
            /*
             * Unaligned 2B access - PCIe allows this but RXM does not.
             * The access must be split into two 1B accesses.
             */
            if (is_read) {
                data[0] = ioread8(addr);
                data[1] = ioread8(addr+1);
            } else {
                iowrite8(data[0], addr);
                iowrite8(data[1], addr+1);
            }
        } else {
            if (is_read) {
                *(uint16_t *)data = ioread16(addr);
            } else {
                iowrite16(*(uint16_t *)data, addr);
            }
        }
    } else { // count == 3
        /*
         * 3B access - PCIe allows this but RXM does not. The access
         * must be split into an aligned 2B access and a 1B access.
         */
        if ((addr_ % 2) == 1) {
            if (is_read) {
                data[0] = ioread8(addr);
                *(uint16_t *)(data+1) = ioread16(addr+1);
            } else {
                iowrite8(data[0], addr);
                iowrite16(*(uint16_t *)(data+1), addr+1);
            }
        } else {
            if (is_read) {
                *(uint16_t *)data = ioread16(addr);
                data[2] = ioread8(addr+2);
            } else {
                iowrite16(*(uint16_t *)data, addr);
                iowrite8(data[2], addr+2);
            }
        }
    }
}

/**
 * rxm_prefetchable_read() - Read a BAR which routes to an RXM and is
 *                           prefetchable.
 * @addr:   EP address to access.
 * @data:   Buffer to save read data.
 * @count:  Number of bytes to access within a DWORD frame.
 *
 * 4 bytes of data is read at some DWORD-aligned address. 1-4 bytes
 * of the data is copied into the data buffer.
 */
static void rxm_prefetchable_single_read(uintptr_t addr, uint8_t *data,
                                         uint16_t count)
{
    // Always read full DW and save data appropriately.
    uint8_t temp_data[4];
    uint8_t offset;
    void __iomem *aligned_addr;

    // Mask lower 2 bits
    aligned_addr = (void __iomem *)(addr & ~((uintptr_t)0x3));
    *(uint32_t *)temp_data = ioread32(aligned_addr);

    offset = addr % 4;
    memcpy(data, temp_data+offset, count);
}

/**
 * round_up_to_po2() - Round _up_ an 16-bit number to the next closest power
 *                     of 2. Bit-twiddling.
 * @num: Number to round up.
 *
 * Return: a power of 2 closest to @num but not less than @num.
 */
static inline uint16_t round_up_to_po2(uint16_t num)
{
    --num;
    num |= num >> 1;
    num |= num >> 2;
    num |= num >> 4;
    num |= num >> 8;
    ++num;
    return num;
}

/**
 * round_down_to_po2_mask() - Find the bit mask for a power of 2 number. The
 *                            power of 2 number is the result of rounding
 *                            _down_ the input to the closest power of 2.
 *                            Bit-twiddling.
 * @num: Number for which to create mask.
 *
 * Return: a bit mask for a number where the number is a power of 2 closest
 *         to @num but not greater than @num.
 */
static inline uint16_t round_down_to_po2_mask(uint16_t num)
{
    num |= num >> 1;
    num |= num >> 2;
    num |= num >> 4;
    num |= num >> 8;
    num >>= 1;
    return num;
}

/*
 * Tip for understanding asm (gcc uses AT&T syntax by default;
 * change with -masm=intel):
 *   asm volatile(
 *     "CMD %0, %1"  // apply CMD from arg 0 to arg 1
 *                   // e.g. mov %0, %1 = move arg0 to arg1
 *     : // Input arguments
 *     : // Output arguments
 *     : // Barrier type
 *   );
 *
 *   So first entry = actual command; second entry = input arguments; third
 *   entry = output arguments; fourth entry = barrier info. Note that the
 *   latter entries can be omitted.
 *   e.g. asm volatile ("mov %0, %%xmm0" : "=m"(data));
 */

/**
 * custom_ioread256_x86() - Read 256-bit data ideally in one 32B transaction.
 *
 * @data: A pointer to the data buffer to save data read from the EP. This
 *        address must be 32B aligned, i.e. the last 5 bits are 0b00000.
 * @addr: A pointer to the EP address to be targeted. This address must be
 *        32B aligned as well.
 *
 * 256-bit movement requires AVX for 256-bit registers ymm0-ymm7/ymm15 as well
 * as the vmovdqa instruction.
 */
static void custom_ioread256_x86(uint8_t *data, void __iomem *addr)
{
#ifdef __AVX__
    // Read from address location to 256-bit ymm1 register.
    asm volatile("vmovdqa %0,%%ymm1"
                 :
                 :"m"(*(volatile uint8_t * __force) addr));

    // Move data from ymm1 to data location.
    asm volatile("vmovdqa %%ymm1,%0":"=m"(*data): :"memory");
#else /* !__AVX__ */
    custom_ioread128_x86(data, addr);
    custom_ioread128_x86(data + 16, addr + 16);
#endif /* !__AVX__ */
}

/**
 * custom_iowrite256_x86() - Write 256-bit data ideally in one 32B transaction.
 *
 * @data: A pointer to the data to write to the EP. This address must
 *        be 32B aligned, i.e. the last 5 bits are 0b00000.
 * @addr: A pointer to the EP address to be targeted. This address must be
 *        32B aligned as well.
 *
 * 256-bit movement requires AVX for 256-bit registers ymm0-ymm7/ymm15 as well
 * as the vmovdqa instruction.
 */
static void custom_iowrite256_x86(uint8_t *data, void __iomem *addr)
{
#ifdef __AVX__
    // Move data to 256-bit ymm1 register.
    asm volatile("vmovdqa %0,%%ymm2": :"m"(*data));

    // Write ymm1 register to address location.
    asm volatile("vmovdqa %%ymm2,%0"
                 :"=m"(*(volatile uint8_t * __force) addr)
                 : :"memory");
#else /* !__AVX__ */
    custom_iowrite128_x86(data, addr);
    custom_iowrite128_x86(data + 16, addr + 16);
#endif /* !__AVX__ */
}

/**
 * custom_ioread128_x86() - Read 128-bit data ideally in one 16B transaction.
 *
 * @data: A pointer to the data buffer to save data read from the EP. This
 *        address must be 16B aligned, i.e. the last 4 bits are 0b0000.
 * @addr: A pointer to the EP address to be targeted. This address must be
 *        16B aligned as well.
 *
 * 128-bit movement requires SSE2 for 128-bit registers xmm0-xmm7/xmm15 as well
 * as the movdqa instruction.
 */
static void custom_ioread128_x86(uint8_t *data, void __iomem *addr)
{
#ifdef __SSE2__
    // Read from address location to 128-bit xmm1 register.
    asm volatile("movdqa %0,%%xmm1"   // %0 -> xmm1 register
                 :                    // no output
                 :"m"(*(volatile uint8_t * __force) addr) // one input
                 : );                 // no clobbering

    // Move data from xmm1 to data location.
    asm volatile("movdqa %%xmm1,%0"   // xmm1 register -> %0
                 :"=m"(*data)         // one output
                 :                    // no input
                 :"memory" );         // clobbers memory
#else /* !__SSE2__ */
    custom_ioread64(data, addr);
    custom_ioread64(data + 8, addr + 8);
#endif /* !__SSE2__ */
}

/**
 * custom_iowrite128_x86() - Write 128-bit data ideally in one 16B transaction.
 *
 * @data: A pointer to the data to write to the EP. This address must
 *        be 16B aligned, i.e. the last 4 bits are 0b0000.
 * @addr: A pointer to the EP address to be targeted. This address must be
 *        16B aligned as well.
 *
 * 128-bit movement requires SSE2 for 128-bit registers xmm0-xmm7/xmm15 as well
 * as the movdqa instruction.
 */
static void custom_iowrite128_x86(uint8_t *data, void __iomem *addr)
{
#ifdef __SSE2__
    // Move data to 128-bit xmm2 register.
    asm volatile("movdqa %0,%%xmm2"   // %0 -> xmm2 register
                 :                    // no output
                 :"m"(*data)          // one input
                 : );                 // no clobbering

    // Write xmm1 register to address location.
    asm volatile("movdqa %%xmm2,%0"   // xmm2 register -> %0
                 :"=m"(*(volatile uint8_t * __force) addr)  // one output
                 : :"memory");        // clobbers memory
#else /* !__SSE2__ */
    custom_iowrite64(data, addr);
    custom_iowrite64(data + 8, addr + 8);
#endif /* !__SSE2__ */
}

/**
 * custom_ioread64() - Read 64-bit data ideally in one 8B transaction.
 *
 * @data: A pointer to the data buffer to save data read from the EP.
 * @addr: A pointer to the EP address to be targeted.
 */
static void custom_ioread64(uint8_t *data, void __iomem *addr)
{
#ifdef CONFIG_64BIT
    *(uint64_t *)data = readq(addr);
#else  /* !CONFIG_64BIT */
    *(uint32_t *)data = ioread32(addr);
    *(uint32_t *)(data + 4) = ioread32(addr + 4);
#endif  /* !CONFIG_64BIT */
}

/**
 * custom_iowrite64() - Write 64-bit data ideally in one 8B transaction.
 *
 * @data: A pointer to the data to write to the EP.
 * @addr: A pointer to the EP address to be targeted.
 *
 */
static void custom_iowrite64(uint8_t *data, void __iomem *addr)
{
#ifdef CONFIG_64BIT
    writeq(*(uint64_t *)data, addr);
#else /* !CONFIG_64BIT */
    iowrite32(*(uint32_t *)data, addr);
    iowrite32(*(uint32_t *)(data + 4), addr + 4);
#endif  /* !CONFIG_64BIT */
}
