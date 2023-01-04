# See LICENSE for license details.

##########################
# Driver Sources & Flags #
##########################

firesim_lib_dir = $(firesim_base_dir)/firesim-lib/src/main/cc
include $(firesim_base_dir)/firesim-lib/src/main/cc/Makefrag

driver_dir = $(firesim_base_dir)/src/main/cc

DRIVER_H = $(shell find $(driver_dir) -name "*.h")

DRIVER_CC := \
		$(driver_dir)/bridges/BridgeHarness.cc \
		$(driver_dir)/bridges/$(DESIGN).cc

DRIVER_CXX_FLAGS := \
		-isystem $(testchipip_csrc_dir) \
		-I$(RISCV)/include \
		-I$(firesim_lib_dir) \
		-I$(driver_dir) \
		-g

DRIVER_LIBS := $(BRIDGES_LIB)

TARGET_LD_FLAGS := $(BRIDGES_LDFLAGS)
