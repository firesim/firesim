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

#ifndef INTEL_FPGA_PCIE_API_HPP
#define INTEL_FPGA_PCIE_API_HPP

/**
 * @file
 * Provides an API to Intel FPGA PCIe devices.
 */

#include <cstdint>
#include <cstddef>
#include <sys/types.h>

#ifdef _WIN32
#endif

#ifdef __linux__
typedef ssize_t INTEL_FPGA_PCIE_DEV_HANDLE;
#  include "intel_fpga_pcie_api_linux.hpp"

#endif // __linux__

/**
 * Intel FPGA PCIe device handle class.
 *
 * This class provides a handle to access an Intel FPGA PCIe device from
 * the user-space without worrying about kernel interface.
 *
 * Successful construction of an object of this class indicates that
 * the handle is properly attached to a device; when no valid device
 * is found in the system, the construction will fail.
 *
 * Two types of access patterns are supported:
 *     -# Single BAR access - manually select BAR each time before accessing
 *        a different BAR using sel_bar().
 *     -# Frequently changing the accessed BAR - use command structure
 *        (use_cmd()) and pass in desired BAR each access.
 *
 * Four methods of accesses are provided:
 *     -# read8(void *, uint8_t *), write8(void *, uint8_t), and similar
 *        functions provide up to 8B access to a previously-selected BAR.
 *     -# read(void *, size_t, void *) and write(void *, size_t, void *)
 *        provide access to a previously-selected BAR without any size
 *        restrictions.
 *     -# read8(int8_t, void *, uint8_t *), write8(int8_t, void *, uint8_t),
 *        and similar functions provide up to 8B access to any BAR.
 *     -# read(int8_t, void *, size_t, void *) and
 *        write(int8_t, void *, size_t, void *) provide access to any BAR
 *        without any size restrictions.
 * For now, the different access methods cannot be interchanged without
 * explicit toggling of use_cmd().
 */
class intel_fpga_pcie_dev
{
public:
    /**
     * Creates a handle to some Intel FPGA PCIe device.
     * If a device with the desired BDF does not exist or it is not an
     * Intel FPGA PCIe device, an exception is thrown. Also, if the desired
     * BAR is not valid, an exception is thrown.
     *
     * @param bdf   The BDF of the device to be selected. If set to 0,
     *              a device with the lowest BDF will be selected by
     *              default.
     * @param bar   The BAR region to access. If set to -1, the lowest
     *              valid BAR is selected by default.
     */
    intel_fpga_pcie_dev(unsigned int bdf=0, int bar=-1);
    ~intel_fpga_pcie_dev(void);

    //@{
    /**
     * @name Configuration space accesses
     * Access the configuration space at an offset.
     * The numeric suffixes in the methods indicate the size of the
     * access, in number of bits.
     *
     */
    /**
     * @param addr      The offset within the configuration space to read.
     * @param data_ptr  Pointer to the location to save the read data.
     * @return 1 on success; 0 otherwise
     */
    int cfg_read8  (void *addr, uint8_t  *data_ptr);
    int cfg_read16 (void *addr, uint16_t *data_ptr);
    int cfg_read32 (void *addr, uint32_t *data_ptr);
    /**
     * @param addr      The offset within the configuration space to write.
     * @param data      The data value to be written.
     * @return 1 on success; 0 otherwise
     */
    int cfg_write8 (void *addr, uint8_t  data);
    int cfg_write16(void *addr, uint16_t data);
    int cfg_write32(void *addr, uint32_t data);
    //@}

    //@{
    /**
     * @name Memory space accesses with a preselected BAR region.
     * Access the memory space at an offset within a previously-selected
     * BAR region.
     * The numeric suffixes in the methods indicate the size of the
     * access, in number of bits.
     */
    /**
     * @param addr      The offset within the BAR region to read.
     * @param data_ptr  Pointer to the location to save the read data.
     * @return 1 on success; 0 otherwise
     */
    int read8  (void *addr, uint8_t  *data_ptr);
    int read16 (void *addr, uint16_t *data_ptr);
    int read32 (void *addr, uint32_t *data_ptr);
    int read64 (void *addr, uint64_t *data_ptr);
    /**
     * @param addr      The offset within the BAR region to write.
     * @param data      The data value to be written.
     * @return 1 on success; 0 otherwise
     */
    int write8 (void *addr, uint8_t  data);
    int write16(void *addr, uint16_t data);
    int write32(void *addr, uint32_t data);
    int write64(void *addr, uint64_t data);
    //@}

    //@{
    /**
     * @name Memory space accesses within any BAR region.
     * Access the memory space at an offset within a desired BAR region.
     * The numeric suffixes in the methods indicate the size of the
     * access, in number of bits.
     */
    /**
     * @param bar       The BAR region to access.
     * @param addr      The offset within the BAR region to read.
     * @param data_ptr  Pointer to the location to save the read data.
     * @return 1 on success; 0 otherwise
     */
    int read8  (unsigned int bar, void *addr, uint8_t  *data_ptr);
    int read16 (unsigned int bar, void *addr, uint16_t *data_ptr);
    int read32 (unsigned int bar, void *addr, uint32_t *data_ptr);
    int read64 (unsigned int bar, void *addr, uint64_t *data_ptr);
    /**
     * @param bar       The BAR region to access.
     * @param addr      The offset within the BAR region to read.
     * @param data      The data value to be written.
     * @return 1 on success; 0 otherwise
     */
    int write8 (unsigned int bar, void *addr, uint8_t  data);
    int write16(unsigned int bar, void *addr, uint16_t data);
    int write32(unsigned int bar, void *addr, uint32_t data);
    int write64(unsigned int bar, void *addr, uint64_t data);
    //@}

    //@{
    /**
     * @name Atypical-sized memory-space accesses within a preselected BAR region.
     * Access the memory space at an offset within a previously-selected
     * BAR region.
     */
    /**
     * @param src       The address of the location to obtain data. For reads,
     *                  the data resides in the device; for writes, the data
     *                  resides in user-space.
     * @param dst       The address of the location to save the data. For
     *                  reads, the data is written to user-space; for writes,
     *                  the data is written to the device.
     * @param count     Number of _bytes_ to access.
     * @return 1 on success; 0 otherwise
     */
    int read(void *src, ssize_t count, void *dst);
    int write(void *dst, ssize_t count, void *src);
    //@}

    //@{
    /**
     * @name Atypical-sized memory-space accesses within any BAR region.
     * Access the memory space at an offset within a desired BAR region.
     */
    /**
     * @param bar       The BAR region to access.
     * @param src       The address of the location to obtain data. For reads,
     *                  the data resides in the device; for writes, the data
     *                  resides in user-space.
     * @param dst       The address of the location to save the data. For
     *                  reads, the data is written to user-space; for writes,
     *                  the data is written to the device.
     * @param count     Number of _bytes_ to access.
     * @return 1 on success; 0 otherwise
     */
    int read(unsigned int bar, void *src, ssize_t count, void *dst);
    int write(unsigned int bar, void *dst, ssize_t count, void *src);
    //@}

    /**
     * Select the device to be accessed.
     * @param bdf The BDF of the device to be selected.
     * @return 1 on success; 0 otherwise
     */
    int sel_dev(unsigned int bdf);

    /**
     * Retrieve the BDF of the device.
     * @return The BDF of the currently selected device.
     */
    unsigned int get_dev(void);

    /**
     * Select the BAR to be accessed.
     * @param bar The BAR number.
     * @return 1 on success; 0 otherwise
     */
    int sel_bar(unsigned int bar);

    /**
     * Retrieve the BAR being accessed.
     * @return The currently selected BAR.
     */
    unsigned int get_bar(void);

    /**
     * Select whether to use the command structure to
     * communicate with the device.
     * @param enable Set to true if using command structure. False if using
     *               standard character read/write interface.
     * @return 1 on success; 0 otherwise
     */
    int use_cmd(bool enable);

    /**
     * Set the number of VFs to be enabled.
     * @param num_vfs Number of VFs to be enabled; 0 indicates disabling
     *                of all VFs.
     * @return 1 on success; 0 otherwise
     */
    int set_sriov_numvfs(unsigned int num_vfs);

    /**
     * Set the size of kernel memory to use.
     * @param size Size of the kernel memory in bytes; setting to 0 frees
     *             the kernel memory.
     * @return 1 on success; 0 otherwise
     */
    int set_kmem_size(unsigned int size);

    /**
     * Map device's kernel memory into user-space
     * @param size Size of the previously-allocated kernel memory to map,
     *             in bytes.
     * @param offset Byte offset within the memory region.
     * @return Address on success; NULL otherwise
     */
    void *kmem_mmap(unsigned int size, unsigned int offset);

    /**
     * Unmap device's kernel memory from user-space
     * @param addr Address of previously-allocated kernel memory's
     *             user-space mapping.
     * @param size Size of the previously-allocated kernel memory to unmap,
     *             in bytes.
     * @return 1 on success; 0 otherwise
     */
    int kmem_munmap(void *addr, unsigned int size);

    /**
     * @param ep_offset     The offset within the end point's address region.
     *                      For reads, this is the source offset;
     *                      for writes, this is the destination offset.
     * @param kmem_offset   The address of the location to save the data. For
     *                  reads, the data is written to user-space; for writes,
     *                  the data is written to the device.
     * @param size      Number of _bytes_ to access.
     * @return 1 on success; 0 otherwise
     */
    int dma_queue_read(uint64_t ep_offset, unsigned int size,
                       uint64_t kmem_offset);
    int dma_queue_write(uint64_t ep_offset, unsigned int size,
                        uint64_t kmem_offset);

    /**
     * Send all queued DMA transfers and wait for their completions.
     */
    int dma_send_read(void);
    int dma_send_write(void);
    int dma_send_all(void);

    /**
     * Retrieve the kernel timer in microseconds.
     * @return Positive integer representing the timer value in microseconds;
     *         0 otherwise
     */
    unsigned int get_ktimer(void);
private:
    INTEL_FPGA_PCIE_DEV_HANDLE m_dev_handle;
    unsigned int m_bdf;
    unsigned int m_bar;
    unsigned int m_kmem_size;
};

#endif /* INTEL_FPGA_PCIE_API_HPP */