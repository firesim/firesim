# See LICENSE for license details.

##########################
# Driver Sources & Flags:
#
# used to point midas compiler to specific *.h and *.cc files, as well as
# corresponding CXX/LD flags.
##########################

DRIVER_H :=
DRIVER_CC := $(firesim_base_dir)/src/main/cc/examples/simple_counter_top.cc
TARGET_CXX_FLAGS := -g
TARGET_LD_FLAGS :=
