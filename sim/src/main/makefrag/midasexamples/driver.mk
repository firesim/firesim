
##########################
# Driver Sources & Flags #
##########################

driver_dir = $(firesim_base_dir)/src/main/cc
firesim_lib_dir = $(firesim_base_dir)/firesim-lib/src/main/cc/
DRIVER_H = $(shell find $(driver_dir) -name "*.h")
DRIVER_CC := \
		$(driver_dir)/midasexamples/TestHarness.cc \
		$(driver_dir)/midasexamples/Test$(DESIGN).cc

TARGET_CXX_FLAGS := \
		-I$(driver_dir) \
		-I$(driver_dir)/midasexamples \
		-g

TARGET_LD_FLAGS :=
