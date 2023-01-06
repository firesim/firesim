# See LICENSE for license details.

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

# Users can override this to point at a makefrag defined in a parent project
# that submodules firesim or source sim/Makefrag directly
TARGET_PROJECT_MAKEFRAG ?= $(firesim_base_dir)/src/main/makefrag/$(TARGET_PROJECT)/Makefrag

# The host-platform type (currently only f1, vitis supported)
PLATFORM ?= f1

####################
#   Target Setup   #
####################

# Include target-specific sources and input generation recipes
include $(TARGET_PROJECT_MAKEFRAG)

# All target-agnostic firesim recipes are defined here
include make/config.mk
include make/library.mk
include make/goldengate.mk
include make/fpga.mk
include make/verilator.mk
include make/vcs.mk
include make/driver.mk
include make/unittest.mk
include make/scala.mk

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

