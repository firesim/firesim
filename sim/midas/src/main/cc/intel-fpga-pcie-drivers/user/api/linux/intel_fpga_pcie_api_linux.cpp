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

#include "intel_fpga_pcie_api.hpp"
#include <stdexcept>

/******************************************************************************
 * Templated access functions.
 *****************************************************************************/
template<typename T>
int linux_read(ssize_t fd, void *addr, T *data_ptr)
{
    ssize_t result;
    result = pread(fd, data_ptr, sizeof(*data_ptr),
                   reinterpret_cast<off_t>(addr));
    return result == sizeof(*data_ptr);
}

template<typename T>
int linux_write(ssize_t fd, void *addr, T *data_ptr)
{
    ssize_t result;
    result = pwrite(fd, data_ptr, sizeof(*data_ptr),
                    reinterpret_cast<off_t>(addr));
    return result == sizeof(*data_ptr);
}

template<typename T>
int linux_bar_read(ssize_t fd, unsigned int bar, void *addr, T *data_ptr)
{
    ssize_t result;
    struct intel_fpga_pcie_cmd cmd;

    cmd.bar_num = bar;
    cmd.bar_offset = reinterpret_cast<uint64_t>(addr);
    cmd.user_addr = data_ptr;
    result = read(fd, &cmd, sizeof(*data_ptr));
    return result == sizeof(*data_ptr);
}

template<typename T>
int linux_bar_write(ssize_t fd, unsigned int bar, void *addr, T *data_ptr)
{
    ssize_t result;
    struct intel_fpga_pcie_cmd cmd;

    cmd.bar_num = bar;
    cmd.bar_offset = reinterpret_cast<uint64_t>(addr);
    cmd.user_addr = data_ptr;
    result = write(fd, &cmd, sizeof(*data_ptr));
    return result == sizeof(*data_ptr);
}

template<typename T>
int linux_cfg_access(ssize_t fd, void *addr, T *data_ptr, bool is_read)
{
    int result;
    struct intel_fpga_pcie_arg arg;

    arg.ep_addr = reinterpret_cast<uint64_t>(addr);
    arg.user_addr = data_ptr;
    arg.size = sizeof(*data_ptr);
    arg.is_read = is_read;

    result = ioctl(fd, INTEL_FPGA_PCIE_IOCTL_CFG_ACCESS, &arg);
    return result == 0;
}

/******************************************************************************
 * Intel FPGA PCIe device class methods
 *****************************************************************************/
intel_fpga_pcie_dev::intel_fpga_pcie_dev(unsigned int bdf, int bar)
{
    ssize_t fd;
    int result;
    unsigned int u_bar;

    fd = open("/dev/intel_fpga_pcie_drv", O_RDWR | O_CLOEXEC);

    if (fd == -1) {
        throw std::runtime_error("could not open character device; "
                                 "ensure that Intel FPGA kernel driver "
                                 "has been loaded");
    }
    m_dev_handle = fd;

    if (bdf > 0) {
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_DEV, bdf);
        if (result != 0) {
            close(m_dev_handle);
            throw std::invalid_argument("could not select desired device");
        }
    } else {
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_GET_DEV, &bdf);
        if (result != 0) {
            close(m_dev_handle);
            throw std::runtime_error("could not retrieve the BDF of the "
                                     "selected device");
        }
    }

    if (bar >= 0) {
        m_bar = static_cast<unsigned int>(bar);
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_BAR, m_bar);
        if (result != 0) {
            close(m_dev_handle);
            throw std::invalid_argument("could not select desired BAR");
        }
    } else {
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_GET_BAR, &u_bar);
        if (result != 0) {
            close(m_dev_handle);
            throw std::runtime_error("could not retrieve the selected BAR");
        }
        m_bar = u_bar;
    }

    m_bdf = bdf;
    m_kmem_size = 0;
};

intel_fpga_pcie_dev::~intel_fpga_pcie_dev(void)
{
    close(m_dev_handle);
};

int intel_fpga_pcie_dev::read8(void *addr, uint8_t *data_ptr)
{
    return linux_read(m_dev_handle, addr, data_ptr);
}

int intel_fpga_pcie_dev::read16(void *addr, uint16_t *data_ptr)
{
    return linux_read(m_dev_handle, addr, data_ptr);
}

int intel_fpga_pcie_dev::read32(void *addr, uint32_t *data_ptr)
{
    return linux_read(m_dev_handle, addr, data_ptr);
}

int intel_fpga_pcie_dev::read64(void *addr, uint64_t *data_ptr)
{
    return linux_read(m_dev_handle, addr, data_ptr);
}

int intel_fpga_pcie_dev::write8(void *addr, uint8_t data)
{
    return linux_write(m_dev_handle, addr, &data);
}

int intel_fpga_pcie_dev::write16(void *addr, uint16_t data)
{
    return linux_write(m_dev_handle, addr, &data);
}

int intel_fpga_pcie_dev::write32(void *addr, uint32_t data)
{
    return linux_write(m_dev_handle, addr, &data);
}

int intel_fpga_pcie_dev::write64(void *addr, uint64_t data)
{
    return linux_write(m_dev_handle, addr, &data);
}

int intel_fpga_pcie_dev::read8(unsigned int bar, void *addr, uint8_t *data_ptr)
{
    return linux_bar_read(m_dev_handle, bar, addr, data_ptr);
}

int intel_fpga_pcie_dev::read16(unsigned int bar, void *addr,
                                uint16_t *data_ptr)
{
    return linux_bar_read(m_dev_handle, bar, addr, data_ptr);
}

int intel_fpga_pcie_dev::read32(unsigned int bar, void *addr,
                                uint32_t *data_ptr)
{
    return linux_bar_read(m_dev_handle, bar, addr, data_ptr);
}

int intel_fpga_pcie_dev::read64(unsigned int bar, void *addr,
                                uint64_t *data_ptr)
{
    return linux_bar_read(m_dev_handle, bar, addr, data_ptr);
}

int intel_fpga_pcie_dev::write8(unsigned int bar, void *addr, uint8_t data)
{
    return linux_bar_write(m_dev_handle, bar, addr, &data);
}

int intel_fpga_pcie_dev::write16(unsigned int bar, void *addr, uint16_t data)
{
    return linux_bar_write(m_dev_handle, bar, addr, &data);
}

int intel_fpga_pcie_dev::write32(unsigned int bar, void *addr, uint32_t data)
{
    return linux_bar_write(m_dev_handle, bar, addr, &data);
}

int intel_fpga_pcie_dev::write64(unsigned int bar, void *addr, uint64_t data)
{
    return linux_bar_write(m_dev_handle, bar, addr, &data);
}

int intel_fpga_pcie_dev::read(void *src, ssize_t count, void *dst)
{
    ssize_t result;
    result = pread(m_dev_handle, dst, count,
                   reinterpret_cast<off_t>(src));
    return result == count;
}

int intel_fpga_pcie_dev::read(unsigned int bar, void *src, ssize_t count,
                              void *dst)
{
    ssize_t result;
    struct intel_fpga_pcie_cmd cmd;

    cmd.bar_num = bar;
    cmd.bar_offset = reinterpret_cast<uint64_t>(src);
    cmd.user_addr = dst;
    result = ::read(m_dev_handle, &cmd, count);
    return result == count;
}

int intel_fpga_pcie_dev::write(void *dst, ssize_t count, void *src)
{
    ssize_t result;
    result = pwrite(m_dev_handle, src, count,
                    reinterpret_cast<off_t>(dst));
    return result == count;
}

int intel_fpga_pcie_dev::write(unsigned int bar, void *dst, ssize_t count,
                               void *src)
{
    ssize_t result;
    struct intel_fpga_pcie_cmd cmd;

    cmd.bar_num = bar;
    cmd.bar_offset = reinterpret_cast<uint64_t>(dst);
    cmd.user_addr = src;
    result = ::write(m_dev_handle, &cmd, count);
    return result == count;
}

int intel_fpga_pcie_dev::cfg_read8(void *addr, uint8_t  *data_ptr)
{
    return linux_cfg_access(m_dev_handle, addr, data_ptr, true);
}

int intel_fpga_pcie_dev::cfg_read16(void *addr, uint16_t *data_ptr)
{
    return linux_cfg_access(m_dev_handle, addr, data_ptr, true);
}

int intel_fpga_pcie_dev::cfg_read32(void *addr, uint32_t *data_ptr)
{
    return linux_cfg_access(m_dev_handle, addr, data_ptr, true);
}

int intel_fpga_pcie_dev::cfg_write8(void *addr, uint8_t  data)
{
    return linux_cfg_access(m_dev_handle, addr, &data, false);
}

int intel_fpga_pcie_dev::cfg_write16(void *addr, uint16_t data)
{
    return linux_cfg_access(m_dev_handle, addr, &data, false);
}

int intel_fpga_pcie_dev::cfg_write32(void *addr, uint32_t data)
{
    return linux_cfg_access(m_dev_handle, addr, &data, false);
}

int intel_fpga_pcie_dev::sel_dev(unsigned int bdf)
{
    int result;
    bdf &= 0xFFFFU;    // BDF is 16 bits.
    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_DEV, bdf);
    if (result == 0) {
        m_bdf = bdf;
    }
    return result == 0;
}

unsigned int intel_fpga_pcie_dev::get_dev(void)
{
    return m_bdf;
}

int intel_fpga_pcie_dev::sel_bar(unsigned int bar)
{
    int result;
    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_SEL_BAR, bar);
    if (result == 0) {
        m_bar = bar;
    }
    return result == 0;
}

unsigned int intel_fpga_pcie_dev::get_bar(void)
{
    return m_bar;
}

int intel_fpga_pcie_dev::use_cmd(bool enable)
{
    int result;
    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_CHR_USE_CMD, enable);
    return result == 0;
}

int intel_fpga_pcie_dev::set_sriov_numvfs(unsigned int num_vfs)
{
    int result;
    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_SRIOV_NUMVFS, num_vfs);
    return result == 0;
}

int intel_fpga_pcie_dev::set_kmem_size(unsigned int size)
{
    int result;
    unsigned int page_size = sysconf(_SC_PAGESIZE);

    // Ensure that at kmem size is at least a page
    if (size >= page_size) {
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_SET_KMEM_SIZE, size);
    } else {
        result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_SET_KMEM_SIZE, page_size);
    }
    
    if (result == 0) {
        m_kmem_size = size;
    }
    return result == 0;
}

void *intel_fpga_pcie_dev::kmem_mmap(unsigned int size, unsigned int offset)
{
    if ((size+offset) > m_kmem_size) {
        return MAP_FAILED;
    }

    return mmap(NULL, size, PROT_READ|PROT_WRITE, MAP_SHARED, m_dev_handle, offset);
};

int intel_fpga_pcie_dev::kmem_munmap(void *addr, unsigned int size)
{
    int result;
    result = munmap(addr, size);

    return result == 0;
};

int intel_fpga_pcie_dev::dma_queue_read(uint64_t ep_offset, unsigned int size,
                                        uint64_t kmem_offset)
{
    int result;
    struct intel_fpga_pcie_arg arg;

    arg.ep_addr = ep_offset;
    arg.user_addr = reinterpret_cast<void *>(kmem_offset);
    arg.size = size;
    arg.is_read = true;

    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_DMA_QUEUE, &arg);
    return result == 0;
}

int intel_fpga_pcie_dev::dma_queue_write(uint64_t ep_offset, unsigned int size,
                                         uint64_t kmem_offset)
{
    int result;
    struct intel_fpga_pcie_arg arg;

    arg.ep_addr = ep_offset;
    arg.user_addr = reinterpret_cast<void *>(kmem_offset);
    arg.size = size;
    arg.is_read = false;

    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_DMA_QUEUE, &arg);
    return result == 0;
}

int intel_fpga_pcie_dev::dma_send_read(void)
{
    int result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_DMA_SEND, 0x1U);
    return result == 0;
}

int intel_fpga_pcie_dev::dma_send_write(void)
{
    int result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_DMA_SEND, 0x2U);
    return result == 0;
}

int intel_fpga_pcie_dev::dma_send_all(void)
{
    int result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_DMA_SEND, 0x3U);
    return result == 0;
}

unsigned int intel_fpga_pcie_dev::get_ktimer(void)
{
    int result;
    unsigned int timer_usec;
    result = ioctl(m_dev_handle, INTEL_FPGA_PCIE_IOCTL_GET_KTIMER, &timer_usec);

    if (result != 0) {
        timer_usec = 0;
    }

    return timer_usec;
}
