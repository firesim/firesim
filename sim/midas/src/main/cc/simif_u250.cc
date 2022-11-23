#include "simif_u250.h"
#include <cassert>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>

#define PCI_DEV_FMT "%04x:%02x:%02x.%d"
int bar0_size = 0x800000; // 8MB

static int
fpga_pci_check_file_id(char *path, uint16_t id)
{
    assert(path);
    int ret = 0;
    FILE *fp = fopen(path, "r");
    assert(fp);
    uint32_t tmp_id;
    ret = fscanf(fp, "%x", &tmp_id);
    assert(ret >= 0);
    assert(tmp_id == id);
    fclose(fp);
    return 0;
}

simif_u250_t::simif_u250_t(int argc, char** argv) {
#ifdef SIMULATION_XSIM
    mkfifo(driver_to_xsim, 0666);
    fprintf(stderr, "opening driver to xsim\n");
    driver_to_xsim_fd = open(driver_to_xsim, O_WRONLY);
    fprintf(stderr, "opening xsim to driver\n");
    xsim_to_driver_fd = open(xsim_to_driver, O_RDONLY);
#else
    slot_id = -1;
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
        if (arg.find("+slotid=") == 0) {
            slot_id = strtol((arg.c_str()) + 8, NULL, 16);
        }
    }
    if (slot_id == -1) {
        fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
        slot_id = 0;
    }
    fpga_setup(slot_id);
#endif


  using namespace std::placeholders;
  auto mmio_read_func  = std::bind(&simif_u250_t::read, this, _1);
  auto pcis_read_func  = std::bind(&simif_u250_t::pcis_read, this, _1, _2, _3);
  auto pcis_write_func = std::bind(&simif_u250_t::pcis_write, this, _1, _2, _3);

  for (int i = 0; i < CPUMANAGEDSTREAMENGINE_0_from_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
      std::string(CPUMANAGEDSTREAMENGINE_0_from_cpu_names[i]),
      CPUMANAGEDSTREAMENGINE_0_from_cpu_dma_addrs[i],
      CPUMANAGEDSTREAMENGINE_0_from_cpu_count_addrs[i],
      CPUMANAGEDSTREAMENGINE_0_from_cpu_buffer_sizes[i]
      );

    from_host_streams.push_back(StreamFromCPU(
      params,
      mmio_read_func,
      pcis_write_func
    ));
  }

  for (int i = 0; i < CPUMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
      std::string(CPUMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
      CPUMANAGEDSTREAMENGINE_0_to_cpu_dma_addrs[i],
      CPUMANAGEDSTREAMENGINE_0_to_cpu_count_addrs[i],
      CPUMANAGEDSTREAMENGINE_0_to_cpu_buffer_sizes[i]);

    to_host_streams.push_back(StreamToCPU(
      params,
      mmio_read_func,
      pcis_read_func
    ));
  }
}

size_t simif_u250_t::pull(unsigned stream_idx, void* dest, size_t num_bytes, size_t threshold_bytes) {
  assert(stream_idx < to_host_streams.size());
  return this->to_host_streams[stream_idx].pull(dest, num_bytes, threshold_bytes);
}

size_t simif_u250_t::push(unsigned stream_idx, void* src, size_t num_bytes, size_t threshold_bytes) {
  assert(stream_idx < from_host_streams.size());
  return this->from_host_streams[stream_idx].push(src, num_bytes, threshold_bytes);
}


void simif_u250_t::check_rc(int rc, char * infostr) {
#ifndef SIMULATION_XSIM
    if (rc) {
        if (infostr) {
            fprintf(stderr, "%s\n", infostr);
        }
        fprintf(stderr, "INVALID RETCODE: %d\n", rc);
        fpga_shutdown();
        exit(1);
    }
#endif
}

void simif_u250_t::fpga_shutdown() {
#ifndef SIMULATION_XSIM
    int ret = munmap(bar0_base, bar0_size);
    assert(ret == 0);
    close(edma_write_fd);
    close(edma_read_fd);
#endif
}

void simif_u250_t::fpga_setup(int slot_id) {
#ifndef SIMULATION_XSIM


    int fd = -1;
    int ret;

    // XDMA setup
    char device_file_name[256];
    char device_file_name2[256];
    char device_file_name3[256];

    int xdma_id = -1;

    xdma_id = slot_id >> 2;
    engine_id = slot_id & 0x3;

    //maxoffset = 0;

    sprintf(device_file_name, "/dev/xdma%d_h2c_%d", xdma_id,engine_id);
    printf("Using xdma write queue: %s\n", device_file_name);
    sprintf(device_file_name2, "/dev/xdma%d_c2h_%d", xdma_id,engine_id);
    printf("Using xdma read queue: %s\n", device_file_name2);


    edma_write_fd = open(device_file_name, O_WRONLY);
    edma_read_fd = open(device_file_name2, O_RDONLY);
    assert(edma_write_fd >= 0);
    assert(edma_read_fd >= 0);

    // Reserve lower 40bits of DMA space
    dma_offset = 0x10000000000;
    //printf("dma_offset: %" PRIu64 "\n", dma_offset);

    sprintf(device_file_name3, "/dev/xdma%d_bypass", xdma_id);
    printf("Using user mmap: %s\n", device_file_name3);
    fd = open(device_file_name3, O_RDWR | O_SYNC);
    assert(fd!=-1);

    bar0_base = mmap(0, bar0_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(bar0_base != MAP_FAILED);

    close(fd);
    fd = -1;

#endif
}



simif_u250_t::~simif_u250_t() {
    fpga_shutdown();
}

void * simif_u250_t::fpga_pci_bar_get_mem_at_offset(uint64_t offset){
    assert(!(((uint64_t)(offset + 4)) > bar0_size));
    return bar0_base + offset;
}

int simif_u250_t::fpga_pci_poke(uint64_t offset, uint32_t value) {
    uint32_t *reg_ptr = (uint32_t *)fpga_pci_bar_get_mem_at_offset(offset);
    *reg_ptr = value;
    return 0;
}

int simif_u250_t::fpga_pci_peek(uint64_t offset, uint32_t *value) {
    uint32_t *reg_ptr = (uint32_t *)fpga_pci_bar_get_mem_at_offset(offset);
    *value = *reg_ptr;
    return 0;
}

void simif_u250_t::write(size_t addr, uint32_t data) {
#ifdef SIMULATION_XSIM
    uint64_t cmd = (((uint64_t)(0x80000000 | addr)) << 32) | (uint64_t)data;
    char * buf = (char*)&cmd;
    ::write(driver_to_xsim_fd, buf, 8);
#else
    int rc = fpga_pci_poke(addr, data);
    check_rc(rc, NULL);
#endif
}


uint32_t simif_u250_t::read(size_t addr) {
#ifdef SIMULATION_XSIM
    uint64_t cmd = addr;
    char * buf = (char*)&cmd;
    ::write(driver_to_xsim_fd, buf, 8);

    int gotdata = 0;
    while (gotdata == 0) {
        gotdata = ::read(xsim_to_driver_fd, buf, 8);
        if (gotdata != 0 && gotdata != 8) {
            printf("ERR GOTDATA %d\n", gotdata);
        }
    }
    return *((uint64_t*)buf);
#else
    uint32_t value;
    int rc = fpga_pci_peek(addr, &value);
    (void) rc;
    return value & 0xFFFFFFFF;
#endif
}

size_t simif_u250_t::pcis_read(size_t addr, char* data, size_t size) {
#ifdef SIMULATION_XSIM
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
#else
  return ::pread(edma_read_fd, data, size, addr|dma_offset);
#endif
}

size_t simif_u250_t::pcis_write(size_t addr, char* data, size_t size) {
#ifdef SIMULATION_XSIM
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
#else
  return ::pwrite(edma_write_fd, data, size, addr|dma_offset);
#endif
}


uint32_t simif_u250_t::is_write_ready() {
    uint64_t addr = 0x4;
#ifdef SIMULATION_XSIM
    uint64_t cmd = addr;
    char * buf = (char*)&cmd;
    ::write(driver_to_xsim_fd, buf, 8);

    int gotdata = 0;
    while (gotdata == 0) {
        gotdata = ::read(xsim_to_driver_fd, buf, 8);
        if (gotdata != 0 && gotdata != 8) {
            printf("ERR GOTDATA %d\n", gotdata);
        }
    }
    return *((uint64_t*)buf);
#else
    uint32_t value;
    int rc = fpga_pci_peek(addr, &value);
    check_rc(rc, NULL);
    return value & 0xFFFFFFFF;
#endif
}
