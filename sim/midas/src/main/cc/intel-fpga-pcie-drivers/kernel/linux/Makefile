MODULE_NAME := intel_fpga_pcie_drv
obj-m += $(MODULE_NAME).o
$(MODULE_NAME)-y := intel_fpga_pcie_chr.o intel_fpga_pcie_dma.o intel_fpga_pcie_setup.o intel_fpga_pcie_ioctl.o
USE_AVX ?= 0

PWD       := $(shell pwd)
KDIR ?= /lib/modules/$(shell uname -r)/build
CPPFLAGS += -include $(KDIR)/include/generated/autoconf.h
EXTRA_CFLAGS += -Wall

ifeq ($(USE_AVX), 1)
	# Enable wide accesses up to 32B
	EXTRA_CFLAGS += -mavx -mpreferred-stack-boundary=4
endif

all:
	$(MAKE) -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) -C $(KDIR) M=$(PWD) clean