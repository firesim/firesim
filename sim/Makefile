##################
#   Parameters   #
##################

<<<<<<< HEAD
# Multiple target-projects, each with it's own chisel generator, co-exist in firesim.
# Their sources exist in:
# src/main/{cc, scala, makefrag}/<target-project-name>
#
# Currently these projects are:
# 	firesim: the default, rocket-chip-based target-designs
# 	midasexamples: simple chisel designs demonstrating how to build midas-style simulators
TARGET_PROJECT ?= firesim
=======
# These point at the main class of the target's Chisel generator
PROJECT ?= firesim
# DESIGN ?= FireSim
# DESIGN ?= FireBoom
DESIGN ?= SupernodeTop

# These guide chisel elaboration of the target design specified above.
# See src/main/scala/SimConfigs.scala
TARGET_PROJECT ?= firesim
# TARGET_CONFIG ?= FireSimRocketChipConfig
# TARGET_CONFIG ?= FireSimBoomConfig
TARGET_CONFIG ?= SupernodeFireSimRocketChipConfig

# These guide chisel elaboration of simulation components by MIDAS, including models and widgets.
# See src/main/scala/SimConfigs.scala
PLATFORM ?= f1
PLATFORM_PROJECT ?= firesim
PLATFORM_CONFIG ?= FireSimConfig

STROBER ?=
DRIVER ?=
SAMPLE ?=
>>>>>>> f6a6dc7... supernode: squashed

# Users can override this to point at a makefrag defined in a parent project
# that submodules firesim or source sim/Makefrag directly
TARGET_PROJECT_MAKEFRAG ?= src/main/makefrag/$(TARGET_PROJECT)/Makefrag

default: compile

SBT ?= sbt
SBT_FLAGS ?= -J-Xmx16G -J-Xss8M -J-XX:MaxPermSize=256M -J-XX:MaxMetaspaceSize=512M -J-XX:ReservedCodeCacheSize=1G
sbt:
	$(SBT) $(SBT_FLAGS)
test:
	$(SBT) $(SBT_FLAGS) test

########################
# Timestamp & Patching #
########################
firesim_base_dir := $(abspath .)
timestamps = $(addprefix $(firesim_base_dir)/, $(addsuffix .timestamp, firrtl))

$(firesim_base_dir)/firrtl.timestamp: $(shell find $(firesim_base_dir)/firrtl/$(src_path) -name "*.scala")
	cd $(firesim_base_dir)/firrtl && $(SBT) $(SBT_FLAGS) publishLocal
	touch $@

PLATFORM := f1

# Include target-specific sources and input generation recipes
include $(TARGET_PROJECT_MAKEFRAG)

verilog: $(VERILOG)
compile: $(VERILOG)

# All target-agnostic firesim recipes are defined here
include Makefrag

