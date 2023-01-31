# See LICENSE for license details.

################################################################################
# Target-Specific  Configuration
################################################################################

# Root name for generated binaries
DESIGN ?=

# The host config package and class string
PLATFORM_CONFIG_PACKAGE ?= firesim.midasexamples
PLATFORM_CONFIG ?= DefaultF1Config

# The host platform type, currently only f1 is supported
PLATFORM ?=

# Driver source files
DRIVER_CC ?=
DRIVER_H ?=

# Target-specific CXX and LD flags for compiling the driver and meta-simulators
# These should be platform independent should be governed by the target-specific makefrag
TARGET_CXX_FLAGS ?=
TARGET_LD_FLAGS ?=

################################################################################
# File and directory setup
################################################################################

# The prefix used for all Golden Gate-generated files
BASE_FILE_NAME := FireSim-generated

name_tuple := $(DESIGN)-$(TARGET_CONFIG)-$(PLATFORM_CONFIG)
long_name := $(DESIGN_PACKAGE).$(DESIGN).$(TARGET_CONFIG)

# The directory into which generated verilog and headers will be dumped
# RTL simulations will also be built here
BUILD_DIR := $(firesim_base_dir)/generated-src
GENERATED_DIR ?= $(BUILD_DIR)/$(PLATFORM)/$(name_tuple)
# Results from RTL simulations live here
OUTPUT_DIR ?= $(firesim_base_dir)/output/$(PLATFORM)/$(name_tuple)

# The target's FIRRTL and associated anotations; inputs to Golden Gate
FIRRTL_FILE := $(GENERATED_DIR)/$(long_name).fir
ANNO_FILE := $(GENERATED_DIR)/$(long_name).anno.json

################################################################################
# Set up a fully-qualified classpath for the target.
################################################################################

# Rocket Chip stage requires a fully qualified classname for each fragment, whereas Chipyard's does not.
# This retains a consistent TARGET_CONFIG naming convention across the different target projects.
subst_prefix :=,$(TARGET_CONFIG_PACKAGE).

TARGET_CONFIG_QUALIFIED := $(TARGET_CONFIG_PACKAGE).$(subst _,$(subst_prefix),$(TARGET_CONFIG))
