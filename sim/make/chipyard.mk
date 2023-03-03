# See LICENSE for license details.

ifdef FIRESIM_STANDALONE

base_dir := $(firesim_base_dir)
chipyard_dir := $(abspath ..)/target-design/chipyard
rocketchip_dir := $(chipyard_dir)/generators/rocket-chip

# Scala invocation options
JVM_MEMORY ?= 16G
SCALA_VERSION ?= 2.13.10
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
export JAVA_TOOL_OPTIONS ?= -Xmx$(JVM_MEMORY) -Xss8M -Dsbt.supershell=false -Djava.io.tmpdir=$(base_dir)/.java_tmp

sbt_sources = $(shell find -L $(base_dir) -name target -prune -o -iname "*.sbt" -print 2> /dev/null)
SCALA_BUILDTOOL_DEPS ?= $(sbt_sources)

SBT ?= java -jar $(rocketchip_dir)/sbt-launch.jar $(SBT_OPTS)

define run_scala_main
	cd $(base_dir) && $(SBT) ";project $(1); runMain $(2) $(3)"
endef

else # FIRESIM_STANDALONE

# Chipyard make variables
base_dir := $(abspath ../../..)
sim_dir := $(firesim_base_dir)
chipyard_dir := $(base_dir)
include $(base_dir)/variables.mk
include $(base_dir)/common.mk

endif

ifdef FIRESIM_STANDALONE
	FIRESIM_SBT_PROJECT := firesim
else
	FIRESIM_SBT_PROJECT := {file:${firesim_base_dir}/}firesim
endif
