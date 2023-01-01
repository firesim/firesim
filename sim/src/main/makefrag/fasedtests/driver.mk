# See LICENSE for license details.

##########################
# Driver Sources & Flags #
##########################

driver_dir = $(firesim_base_dir)/src/main/cc
DRIVER_H = $(shell find $(driver_dir) -name "*.h")
DRIVER_CC = $(wildcard $(addprefix $(driver_dir)/, $(addsuffix .cc, fasedtests/* firesim/systematic_scheduler)))

TARGET_CXX_FLAGS := -g -O2 -I$(driver_dir) -I$(driver_dir)/fasedtests -I$(RISCV)/include
TARGET_LD_FLAGS :=
