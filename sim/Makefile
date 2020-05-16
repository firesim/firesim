# See LICENSE for license details.

ifndef FIRESIM_ENV_SOURCED
$(error You must source sourceme-f1-manager.sh or env.sh to use this Makefile)
endif

firesim_base_dir := $(abspath .)

default: compile

##################
#   Parameters   #
##################

# Multiple target-projects, each with it's own chisel generator, co-exist in firesim.
# Their sources exist in:
# src/main/{cc, scala, makefrag}/<target-project-name>
#
# Currently these projects are:
# 	firesim: the default, rocket-chip-based target-designs
# 	midasexamples: simple chisel designs demonstrating how to build midas-style simulators
TARGET_PROJECT ?= firesim

# Users can override this to point at a makefrag defined in a parent project
# that submodules firesim or source sim/Makefrag directly
TARGET_PROJECT_MAKEFRAG ?= $(firesim_base_dir)/src/main/makefrag/$(TARGET_PROJECT)/Makefrag

# The host-platform type (currently only f1 supported)
PLATFORM ?= f1

ifdef FIRESIM_STANDALONE
base_dir := $(firesim_base_dir)
chipyard_dir := $(abspath ..)/target-design/chipyard
else # Chipyard is top
base_dir := $(abspath ../../..)
chipyard_dir := $(base_dir)
endif
sim_dir := $(firesim_base_dir)

include $(chipyard_dir)/variables.mk
include $(chipyard_dir)/common.mk

# Include target-specific sources and input generation recipes
include $(TARGET_PROJECT_MAKEFRAG)

verilog: $(VERILOG)
compile: $(VERILOG)

# Phony targets for launching the sbt shell and running scalatests
.PHONY: sbt
sbt: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "shell"
.PHONY: test
test: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "test"
.PHONY: testOnly
testOnly: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "testOnly $(SCALA_TEST)"
# All target-agnostic firesim recipes are defined here
include target-agnostic.mk

