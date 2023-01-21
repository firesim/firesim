# See LICENSE for license details.

# Defines make targets for:
# - invoking Golden Gate (phony: verilog / compile)
# - building a simulation driver (phony: f1)
# - populating an FPGA build directory (phony: replace-rtl)
# - generating new runtime configurations (phony: conf)
# - compiling meta-simulators (phony: verilator, vcs, verilator-debug, vcs-debug)

.PHONY: default
default: compile

ifndef FIRESIM_ENV_SOURCED
$(error You must source sourceme-f1-manager.sh or env.sh to use this Makefile)
endif

firesim_base_dir := $(abspath .)

# Set up environment variables pointing to Chipyard and SBT.
include make/chipyard.mk

##################
#   Parameters   #
##################

# Multiple target-projects, each with their own chisel generator, co-exist in firesim.
# Their sources exist in:
# src/main/{cc, scala, makefrag}/<target-project-name>
#
# Currently these projects are:
# 	firesim: the default, Chipyard-based target-designs
# 	midasexamples: simple chisel designs demonstrating FireSim's features
TARGET_PROJECT ?= firesim

# Users can override this to point at a collections of Makefrags.
# The following makefrags must be present in the folder:
#   config.mk: override configuration variables with project specifics
#   build.mk: define the build rule generating the input FIRRTL and annotations
#   driver.mk: define the configuration for the driver
#   metasim.mk: define rules to run metasimulator binaries.
TARGET_PROJECT_MAKEFRAG ?= $(firesim_base_dir)/src/main/makefrag/$(TARGET_PROJECT)

# The host-platform type (currently only f1, vitis supported)
PLATFORM ?= f1

####################
#   Target Setup   #
####################

include $(TARGET_PROJECT_MAKEFRAG)/config.mk
include make/config.mk
include make/scala-build.mk

include $(TARGET_PROJECT_MAKEFRAG)/build.mk
include make/goldengate.mk

include $(TARGET_PROJECT_MAKEFRAG)/driver.mk
include make/library.mk

include make/driver.mk
include make/fpga.mk
include make/verilator.mk
include make/vcs.mk
include make/xsim.mk

include $(TARGET_PROJECT_MAKEFRAG)/metasim.mk

include make/unittest.mk
include make/scala-lint.mk
include make/cpp-lint.mk

#########################
# Cleaning Recipes      #
#########################
.PHONY: mostlyclean
mostlyclean:
	rm -rf $(verilator) $(verilator_debug) $(vcs) $(vcs_debug) $($(PLATFORM)) $(OUTPUT_DIR)

.PHONY: clean
clean:
	rm -rf $(GENERATED_DIR) $(OUTPUT_DIR)

.PHONY: veryclean
veryclean:
	rm -rf generated-src output

# Remove all implicit suffix rules; This improves make performance substantially as it no longer
# attempts to resolve implicit rules on 1000+ scala files.
.SUFFIXES:

