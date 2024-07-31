#include "fpga_managed_stream.h"
#include "core/simif.h"

#include <assert.h>
#include <cstring>
#include <inttypes.h>
#include <iostream>
#include <sstream>
#include <stdio.h>
#include <string>
#include <vector>

void FPGAManagedStreams::FPGAToCPUDriver::init() {
  fprintf(stdout, "PCIM Peer Base Addr: 0x%" PRIx64 "\n", buffer_base_fpga);
  mmio_write(params.toHostPhysAddrHighAddr, (uint32_t)(buffer_base_fpga >> 32));
  mmio_write(params.toHostPhysAddrLowAddr, (uint32_t)buffer_base_fpga);
}
/**
 * @brief Dequeues as much as num_bytes of data from the associated bridge
 * stream.
 *
 * @param dest  Buffer into which to copy dequeued stream data
 * @param num_bytes  Bytes of data to dequeue
 * @param required_bytes  Minimum number of bytes to dequeue. If fewer bytes
 * would be dequeued, dequeue none and return 0.
 * @return size_t Number of bytes successfully dequeued
 */
size_t FPGAManagedStreams::FPGAToCPUDriver::pull(void *dest,
                                                 size_t num_bytes,
                                                 size_t required_bytes) {
  assert(num_bytes >= required_bytes);
  size_t bytes_in_buffer = mmio_read(params.bytesAvailableAddr);
  if (bytes_in_buffer < required_bytes) {
    return 0;
  }

  void *src_addr = (char *)buffer_base + buffer_offset;
  size_t first_copy_bytes =
      ((buffer_offset + bytes_in_buffer) > params.buffer_capacity)
          ? params.buffer_capacity - buffer_offset
          : bytes_in_buffer;
  std::memcpy(dest, src_addr, first_copy_bytes);
  if (first_copy_bytes < bytes_in_buffer) {
    std::memcpy((char *)dest + first_copy_bytes,
                buffer_base,
                bytes_in_buffer - first_copy_bytes);
  }
  buffer_offset = (buffer_offset + bytes_in_buffer) % params.buffer_capacity;
  mmio_write(params.bytesConsumedAddr, bytes_in_buffer);
  return bytes_in_buffer;
}

void FPGAManagedStreams::FPGAToCPUDriver::flush() {
  mmio_write(params.toHostStreamFlushAddr, 1);
  // TODO: Consider if this should be made non-blocking // alternate API
  auto flush_done = false;
  int attempts = 0;
  while (!flush_done) {
    flush_done = (mmio_read(params.toHostStreamFlushDoneAddr) & 1);
    if (++attempts > 256) {
      exit(1); // Bridge stream flush appears to deadlock
    };
  }
}

FPGAManagedStreamWidget::FPGAManagedStreamWidget(
    simif_t &simif,
    unsigned index,
    const std::vector<std::string> &args,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu) {
  assert(index == 0 && "only one managed stream engine is allowed");

  auto &io = simif.get_fpga_managed_stream_io();

  int idx = 0;
  bool found = false;
  const char *resource_name[8] = {};

  // TODO : Currently using the number of FPGAs in the partition as a proxy of
  // the AWS instance type Need a better way of doing this...
  int f1_instance_fpga_cnt = 2;
  std::string fpga_cnt_arg = std::string("+partition-fpga-cnt=");
  for (auto &arg : args) {
    if (arg.find(fpga_cnt_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + fpga_cnt_arg.length();
      int fpga_cnt = atoi(str);
      f1_instance_fpga_cnt = fpga_cnt > 2 ? 8 : 2;
    }
  }
  /* printf("f1_instance_fpga_cnt: %d\n", f1_instance_fpga_cnt); */

  if (f1_instance_fpga_cnt == 2) {
    resource_name[0] = "0000:00:1b.0";
    resource_name[1] = "0000:00:1d.0";
  } else if (f1_instance_fpga_cnt == 8) {
    resource_name[0] = "0000:00:0f.0";
    resource_name[1] = "0000:00:11.0";
    resource_name[2] = "0000:00:13.0";
    resource_name[3] = "0000:00:15.0";
    resource_name[4] = "0000:00:17.0";
    resource_name[5] = "0000:00:19.0";
    resource_name[6] = "0000:00:1b.0";
    resource_name[7] = "0000:00:1d.0";
  }

  uint64_t p2p_bar4_addrs[f1_instance_fpga_cnt];
  for (int slotid = 0; slotid < f1_instance_fpga_cnt; slotid++) {
    p2p_bar4_addrs[slotid] = get_p2p_bar_address(resource_name[slotid]);
  }

  std::vector<uint64_t> pcis_offsets;
  do {
    std::string peer_pcis_offset_args = std::string("+peer-pcis-offset") +
                                        std::to_string(idx) + std::string("=");
    found = false;
    for (auto &arg : args) {
      if (arg.find(peer_pcis_offset_args) == 0) {
        found = true;
        char *c_str =
            const_cast<char *>(arg.c_str() + peer_pcis_offset_args.length());
        std::string cpp_str = c_str;
        std::stringstream ss(cpp_str);
        std::string token;
        std::vector<std::string> words;
        while (getline(ss, token, ',')) {
          words.push_back(token);
        }

        int slotid = std::stoi(words[0]);
        int bridge_offset = std::stoi(words[1]);
        uint64_t offset = p2p_bar4_addrs[slotid] + bridge_offset;
        printf("P2P neighbor slotid: %d, p2p_bar4_addrs: 0x%" PRIx64
               " bridge_offset: 0x%x offset: 0x%" PRIx64 "\n",
               slotid,
               p2p_bar4_addrs[slotid],
               bridge_offset,
               offset);

        pcis_offsets.push_back(offset);
      }
    }
    idx++;
  } while (found);

  idx = 0;
  for (auto &&params : to_cpu) {
    uint32_t capacity = params.buffer_capacity;
    uint64_t offset = pcis_offsets[idx];
    fpga_to_cpu_streams.push_back(
        std::make_unique<FPGAManagedStreams::FPGAToCPUDriver>(
            std::move(params), (void *)(offset), offset, io));
    idx++;
  }
}

uint64_t FPGAManagedStreamWidget::get_p2p_bar_address(const char *dir_name) {
  int ret;
  uint64_t physical_addr = 0;
  if (!dir_name) {
    printf("dir_name is null\n");
    assert(false);
  }

  char sysfs_name[256];
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/%s/resource",
                 dir_name);

  if (ret < 0) {
    printf("Error building the sysfs path for resource\n");
    assert(false);
  }
  if ((size_t)ret >= sizeof(sysfs_name)) {
    printf("sysfs path too long for resource\n");
    assert(false);
  }

  FILE *fp = fopen(sysfs_name, "r");
  if (!fp) {
    printf("Error opening %s\n", sysfs_name);
    assert(false);
  }

#ifndef FPGA_BAR_PER_PF_MAX
#define FPGA_BAR_PER_PF_MAX 5
#endif

  for (size_t i = 0; i < FPGA_BAR_PER_PF_MAX; ++i) {
    uint64_t addr_begin = 0, addr_end = 0, flags = 0;
    ret = fscanf(fp, "0x%lx 0x%lx 0x%lx\n", &addr_begin, &addr_end, &flags);
    if (ret < 3 || addr_begin == 0) {
      continue;
    }
    if (i == 4) {
      physical_addr = addr_begin;
    }
  }
  fclose(fp);

  printf("get_p2p_bar_address physical_addr: 0x%" PRIx64 "\n", physical_addr);
  assert(physical_addr != 0 && "Unable to get valid physical_addr");

  return physical_addr;
}
