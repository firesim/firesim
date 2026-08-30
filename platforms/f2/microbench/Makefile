# See LICENSE for license details.
#
# Phase A0 microbenchmark. Standalone: does not depend on the FireSim build.

SDK_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../aws-fpga-firesim-f2/sdk/userspace)

CC      ?= gcc
CFLAGS  += -O2 -Wall -Wextra -std=gnu11 -I$(SDK_DIR)/include
LDFLAGS += -L$(SDK_DIR)/lib
LDLIBS  += -lfpga_mgmt -lz

TARGET  := pcie_mmio_latency

.PHONY: all clean check-sdk

all: check-sdk $(TARGET)

check-sdk:
	@test -f $(SDK_DIR)/lib/libfpga_mgmt.a || { \
	  echo "ERROR: $(SDK_DIR)/lib/libfpga_mgmt.a not found."; \
	  echo "Build the AWS SDK first:"; \
	  echo "    source $(abspath $(SDK_DIR)/../..)/sdk_setup.sh"; \
	  exit 1; \
	}

$(TARGET): $(TARGET).c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS) $(LDLIBS)

clean:
	rm -f $(TARGET)
