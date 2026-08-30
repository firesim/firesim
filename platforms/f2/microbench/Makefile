# See LICENSE for license details.
#
# Phase A0 microbenchmark. Standalone: does not depend on the FireSim build.

SDK_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../aws-fpga-firesim-f2/sdk/userspace)

CC      ?= gcc
CFLAGS  += -O2 -Wall -Wextra -std=gnu11 -I$(SDK_DIR)/include
LDFLAGS += -L$(SDK_DIR)/lib
LDLIBS  += -lz

TARGETS := pcie_mmio_latency dma_buffer_check

.PHONY: all clean check-sdk

all: check-sdk $(TARGETS)

check-sdk:
	@test -f $(SDK_DIR)/lib/libfpga_mgmt.a || { \
	  echo "ERROR: $(SDK_DIR)/lib/libfpga_mgmt.a not found."; \
	  echo "Build the AWS SDK first:"; \
	  echo "    source $(abspath $(SDK_DIR)/../..)/sdk_setup.sh"; \
	  exit 1; \
	}

# dma_buffer_check additionally needs the DMA buffer helpers.
pcie_mmio_latency: LDLIBS += -lfpga_mgmt
dma_buffer_check:  LDLIBS += -lfpga_mgmt -lfpga_dma

%: %.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS) $(LDLIBS)

clean:
	rm -f $(TARGETS)
