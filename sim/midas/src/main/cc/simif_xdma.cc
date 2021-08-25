#include "simif_xdma.h"
#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <unistd.h>

simif_xdma_t::simif_xdma_t(int argc, char** argv) {
    slot_id = -1;
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
        if (arg.find("+slotid=") == 0) {
            slot_id = atoi((arg.c_str()) + 8);
        }
    }
    if (slot_id == -1) {
        fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
        slot_id = 0;
    }
    fpga_setup(slot_id);
}

void simif_xdma_t::fpga_shutdown() {
	munmap(ctrl_base_vaddr, ctrl_as_size);
    close(ctrl_fd);
    close(xdma_write_fd);
    close(xdma_read_fd);
}

void simif_xdma_t::fpga_setup(int slot_id) {
    // TODO: check device ids?.
    /*
     * pci_vendor_id and pci_device_id values below are Amazon's and avaliable
     * to use for a given FPGA slot.
     * Users may replace these with their own if allocated to them by PCI SIG
     */
    // uint16_t pci_vendor_id = 0x1D0F; /* Amazon PCI Vendor ID */
    // uint16_t pci_device_id = 0xF000; /* PCI Device ID preassigned by Amazon for F1 applications */


	/* MMAP the control bus memory space to implement PEEK / POKE */
    char ctrl_device_file_name[256];
    sprintf(ctrl_device_file_name, "/dev/xdma%d_user", slot_id);
    printf("Using %s to implement simulator ctrl interface.", ctrl_device_file_name);

	ctrl_fd = open(ctrl_device_file_name, O_RDWR | O_SYNC);
    if (ctrl_fd == NULL) {
        perror("Could not open file descripter for ctrl IF:");
        abort();
    }


	ctrl_base_vaddr = mmap(0, ctrl_as_size, PROT_READ | PROT_WRITE, MAP_SHARED, ctrl_fd, 0);
	if (ctrl_base_vaddr == MAP_FAILED) {
        perror("mmap for ctrl interface failed:");
        abort();
    }

    // XDMA setup
    char device_file_name[256];
    char device_file_name2[256];

    sprintf(device_file_name, "/dev/xdma%d_h2c_0", slot_id);
    printf("Using xdma write queue: %s\n", device_file_name);
    sprintf(device_file_name2, "/dev/xdma%d_c2h_0", slot_id);
    printf("Using xdma read queue: %s\n", device_file_name2);


    xdma_write_fd = open(device_file_name, O_WRONLY);
    xdma_read_fd = open(device_file_name2, O_RDONLY);
    assert(xdma_write_fd >= 0);
    assert(xdma_read_fd >= 0);
}



simif_xdma_t::~simif_xdma_t() {
    fpga_shutdown();
}

void simif_xdma_t::write(size_t addr, uint32_t data) {
    // NB: addr is natively a 32b word address
    printf("Writing %x to address: %d\n", data, addr);
    *(((uint32_t *) ctrl_base_vaddr) + addr) = data;
}

uint32_t simif_xdma_t::read(size_t addr) {
    // NB: addr is natively 32b word address
    printf("Reading from address: %d\n", addr);
    uint32_t result = *(((uint32_t *) ctrl_base_vaddr) + addr);
    printf("   Got:  %x\n", result);
    return result;
}

ssize_t simif_xdma_t::pull(size_t addr, char* data, size_t size) {
  return ::pread(xdma_read_fd, data, size, addr);
}

ssize_t simif_xdma_t::push(size_t addr, char* data, size_t size) {
  return ::pwrite(xdma_write_fd, data, size, addr);
}
