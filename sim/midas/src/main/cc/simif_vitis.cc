#include "simif_vitis.h"
#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

simif_vitis_t::simif_vitis_t(int argc, char** argv) {
    device_index = -1;
    binary_file = "";

    // TODO: Properly read out arguments
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
        if (arg.find("+device_index=") == 0) {
            device_index = atoi((arg.c_str()) + 14); // 0
        }
        if (arg.find("+binary_file=") == 0) {
            binary_file = arg.substr(13, std::string::npos);//"kernel.xclbin"; //atoi((arg.c_str()) + 8);
        }
    }

    if (device_index == -1) {
        fprintf(stderr, "Device ID not specified. Assuming Device 0.\n");
        device_index = 0;
    }

    if (binary_file == "") {
        fprintf(stderr, "No binary file specified.\n");
        exit(1);
    }

    fprintf(stdout, "DEBUG: DevIdx:%d XCLBin:%s\n", device_index, binary_file.c_str());

    // Open the FPGA device
    device_handle = xrt::device(device_index);

    // Load the XCLBIN (get handle, then load)
    uuid = device_handle.load_xclbin(binary_file);

    // Open Kernel
    kernel_handle = xrt::ip(device_handle, uuid, "firesim");

    fprintf(stdout, "DEBUG: Successfully opened kernel\n");

}

simif_vitis_t::~simif_vitis_t() {
    fprintf(stdout, "Graceful shutdown\n");
}

void simif_vitis_t::write(size_t addr, uint32_t data) {
    // addr is really a (32-byte) word address because of zynq implementation
    addr <<= CTRL_AXI4_SIZE;
    kernel_handle.write_register(addr, data);

    fprintf(stdout, "DEBUG: Write 0x%lx:%d\n", addr, data);
    exit(1);
}

uint32_t simif_vitis_t::read(size_t addr) {
    // Convert the word address into a byte-address
    addr <<= CTRL_AXI4_SIZE;
    uint32_t value;
    value = kernel_handle.read_register(addr);

    fprintf(stdout, "DEBUG: Read 0x%lx:%d\n", addr, value);
    exit(1);

    return value & 0xFFFFFFFF;
}

// TODO: Not implemented
ssize_t simif_vitis_t::pull(size_t addr, char* data, size_t size) {
  return -1; //::pread(edma_read_fd, data, size, addr);
}

// TODO: Not implemented
ssize_t simif_vitis_t::push(size_t addr, char* data, size_t size) {
  return -1; //::pwrite(edma_write_fd, data, size, addr);
}

uint32_t simif_vitis_t::is_write_ready() {
    uint64_t addr = 0x4;
    uint32_t value;
    value = kernel_handle.read_register(addr);

    fprintf(stdout, "DEBUG: Read-is_write_ready() 0x%lx:%d\n", addr, value);
    exit(1);

    return value & 0xFFFFFFFF;
}
