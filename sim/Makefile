##################
#   Parameters   #
##################

firesim_base_dir := $(abspath .)

default: compile

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

FIRESIM_AS_LIBRARY:=1
ifneq ($(FIRESIM_AS_LIBRARY), 1)

$(info Firesim standalone)

base_dir := $(firesim_base_dir)

SBT ?= sbt
JVM_MEMORY ?= 16G
SBT_FLAGS ?= -J-Xmx$(JVM_MEMORY) ++2.12.4

# Manage the FIRRTL dependency manually
FIRRTL_SUBMODULE_DIR ?= $(firesim_base_dir)/firrtl
FIRRTL_JAR ?= $(FIRRTL_SUBMODULE_DIR)/utils/bin/firrtl.jar
$(FIRRTL_JAR): $(shell find $(FIRRTL_SUBMODULE_DIR)/src/main/scala -iname "*.scala")
	$(MAKE) -C $(FIRRTL_SUBMODULE_DIR) SBT="$(SBT) $(SBT_FLAGS)" root_dir=$(FIRRTL_SUBMODULE_DIR) build-scala
	touch $(FIRRTL_JAR)
	mkdir -p $(firesim_base_dir)/lib
	cp -p $(FIRRTL_JAR) $(firesim_base_dir)/lib/

firrtl: $(FIRRTL_JAR)
.PHONY: firrtl

else

$(info FireSim as a library)

# REBAR make variables
base_dir := $(firesim_base_dir)/../../
sim_dir := $(firesim_base_dir)
include $(base_dir)/Makefrag

endif

# Include target-specific sources and input generation recipes
include $(TARGET_PROJECT_MAKEFRAG)

verilog: $(VERILOG)
compile: $(VERILOG)

# Phony targets for launching the sbt shell and running scalatests
sbt: $(FIRRTL_JAR)
	cd $(base_dir) && $(SBT) $(SBT_FLAGS) shell
test: $(FIRRTL_JAR)
	cd $(base_dir) && $(SBT) $(SBT_FLAGS) test

PLATFORM := f1

# All target-agnostic firesim recipes are defined here
include firesim-target-agnostic.mk

