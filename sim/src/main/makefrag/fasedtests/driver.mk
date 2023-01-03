# See LICENSE for license details.

##########################
# Driver Sources & Flags #
##########################

driver_dir = $(firesim_base_dir)/src/main/cc

DRIVER_H = $(shell find $(driver_dir) -name "*.h")

DRIVER_CC =  \
	  $(driver_dir)/fasedtests/fasedtests_top.cc \
	  $(driver_dir)/fasedtests/test_harness_bridge.cc

DRIVER_CXX_FLAGS := \
	-I$(driver_dir) \
	-I$(driver_dir)/fasedtests

TARGET_LD_FLAGS :=
