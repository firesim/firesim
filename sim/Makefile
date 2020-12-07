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
rocketchip_dir := $(chipyard_dir)/generators/rocket-chip

# Scala invocation options
JVM_MEMORY ?= 96G
SCALA_VERSION ?= 2.12.10
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
JAVA_ARGS ?= -Xmx$(JVM_MEMORY) -Xss65G -Dsbt.supershell=false
SBT ?= java $(JAVA_ARGS) -jar $(rocketchip_dir)/sbt-launch.jar

BLOOP ?= bloop
BLOOP_CONFIG_DIR ?= $(base_dir)/.bloop
# This mirrors the bloop default. Set to a system-unique port in a multi-user environment
BLOOP_NAILGUN_PORT ?= 8212

SCALA_BUILDTOOL_DEPS ?= build.sbt

ifdef ENABLE_BLOOP
override SCALA_BUILDTOOL_DEPS += $(BLOOP_CONFIG_DIR)/TIMESTAMP
# Two notes about the bloop invocation:
# 1) the sed removes a leading {file:<path>} that sometimes needs to be
#    provided to SBT when a project but not for bloop.
# 2) Generally, one could could pass '--' to indicate all remaining arguments are
# 	 destined for the scala Main, however a bug in Bloop's argument parsing causes the
#    --nailgun-port argument to be lost in this case. Workaround this by prefixing
#    every main-destined argument with "--args"
define run_scala_main
	cd $(base_dir) && bloop --nailgun-port $(BLOOP_NAILGUN_PORT) run $(shell echo $(1) | sed 's/{.*}//') --main $(2)  $(addprefix --args ,$3)
endef
else
define run_scala_main
	cd $(base_dir) && $(SBT) "project $(1)" "runMain $(2) $(3)"
endef
endif

#########################
# Bloop Project Defs    #
#########################
sbt_sources = $(shell find -L $(base_dir) -name target -prune -o -iname "*.sbt" -print 2> /dev/null)
$(BLOOP_CONFIG_DIR)/TIMESTAMP: $(sbt_sources)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "bloopInstall"
	touch $@

# Manage the FIRRTL dependency manually
FIRRTL_SUBMODULE_DIR ?= $(chipyard_dir)/tools/firrtl
FIRRTL_JAR ?= $(chipyard_dir)/lib/firrtl.jar
FIRRTL_TEST_JAR ?= $(chipyard_dir)/test_lib/firrtl.jar

firrtl_srcs := $(shell find $(FIRRTL_SUBMODULE_DIR) -iname "[!.]*.scala")

$(FIRRTL_JAR): $(firrtl_srcs)
	$(MAKE) -C $(FIRRTL_SUBMODULE_DIR) SBT="$(SBT)" root_dir=$(FIRRTL_SUBMODULE_DIR) build-scala
	mkdir -p $(@D)
	touch $(FIRRTL_SUBMODULE_DIR)/utils/bin/firrtl.jar
	cp -p $(FIRRTL_SUBMODULE_DIR)/utils/bin/firrtl.jar $@

$(FIRRTL_TEST_JAR): $(firrtl_srcs)
	cd $(FIRRTL_SUBMODULE_DIR) && $(SBT) "test:assembly"
	mkdir -p $(@D)
	touch $(FIRRTL_SUBMODULE_DIR)/utils/bin/firrtl-test.jar
	cp -p $(FIRRTL_SUBMODULE_DIR)/utils/bin/firrtl-test.jar $@

firrtl: $(FIRRTL_JAR)
.PHONY: firrtl

else
# Chipyard make variables
base_dir := $(abspath ../../..)
sim_dir := $(firesim_base_dir)
chipyard_dir := $(base_dir)
include $(base_dir)/variables.mk
include $(base_dir)/common.mk
endif

# Include target-specific sources and input generation recipes
include $(TARGET_PROJECT_MAKEFRAG)

verilog: $(VERILOG)
compile: $(VERILOG)

# Phony targets for launching the sbt shell and running scalatests
SBT_COMMAND ?= shell
.PHONY: sbt
sbt: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "$(SBT_COMMAND)"
.PHONY: test
test: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "test"
.PHONY: testOnly
testOnly: $(FIRRTL_JAR) $(FIRRTL_TEST_JAR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "testOnly $(SCALA_TEST)"
# All target-agnostic firesim recipes are defined here
include target-agnostic.mk

