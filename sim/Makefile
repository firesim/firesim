# See LICENSE for license details.

ifndef FIRESIM_ENV_SOURCED
$(error You must source sourceme-f1-manager.sh or env.sh to use this Makefile)
endif

firesim_base_dir := $(abspath .)

.PHONY: default
default: compile

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

# The host-platform type (currently only f1 supported)
PLATFORM ?= f1

ifdef FIRESIM_STANDALONE

base_dir := $(firesim_base_dir)
chipyard_dir := $(abspath ..)/target-design/chipyard
rocketchip_dir := $(chipyard_dir)/generators/rocket-chip

# Scala invocation options
JVM_MEMORY ?= 16G
SCALA_VERSION ?= 2.12.10
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
JAVA_OPTS ?= -Xmx$(JVM_MEMORY) -Dsbt.supershell=false

sbt_sources = $(shell find -L $(base_dir) -name target -prune -o -iname "*.sbt" -print 2> /dev/null)
SCALA_BUILDTOOL_DEPS ?= $(sbt_sources)

SBT_THIN_CLIENT_TIMESTAMP = $(base_dir)/project/target/active.json

ifdef ENABLE_SBT_THIN_CLIENT
override SCALA_BUILDTOOL_DEPS += $(SBT_THIN_CLIENT_TIMESTAMP)
# enabling speeds up sbt loading
SBT_CLIENT_FLAG = --client
endif

SBT ?= java $(JAVA_OPTS) -jar $(rocketchip_dir)/sbt-launch.jar $(SBT_OPTS) $(SBT_CLIENT_FLAG)

define run_scala_main
	cd $(base_dir) && $(SBT) ";project $(1); runMain $(2) $(3)"
endef

##############################################################################
# SBT Server Setup (start server / rebuild proj. defs. if SBT_SOURCES change)
##############################################################################
$(SBT_THIN_CLIENT_TIMESTAMP): $(SBT_SOURCES)
ifneq (,$(wildcard $(SBT_THIN_CLIENT_TIMESTAMP)))
	cd $(base_dir) && $(SBT) "reload"
	touch $@
else
	cd $(base_dir) && $(SBT) "exit"
endif

.PHONY: shutdown-sbt-server
shutdown-sbt-server:
	cd $(base_dir) && $(SBT) "shutdown"

.PHONY: start-sbt-server
start-sbt-server:
	cd $(base_dir) && $(SBT) "exit"

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


# Phony targets for launching the sbt shell and running scalatests
SBT_COMMAND ?= shell
SBT_NON_THIN ?= $(subst $(SBT_CLIENT_FLAG),,$(SBT))
.PHONY: sbt
sbt:
	cd $(base_dir) && $(SBT_NON_THIN) ";project $(firesim_sbt_project); $(SBT_COMMAND)"
.PHONY: test
test:
	cd $(base_dir) && $(SBT_NON_THIN) ";project $(firesim_sbt_project); test"
.PHONY: testOnly
testOnly:
	cd $(base_dir) && $(SBT_NON_THIN) ";project $(firesim_sbt_project); testOnly $(SCALA_TEST)"

# All target-agnostic firesim recipes are defined here
include target-agnostic.mk

