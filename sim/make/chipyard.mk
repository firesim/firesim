# See LICENSE for license details.

ifdef FIRESIM_STANDALONE
base_dir := $(firesim_base_dir)
chipyard_dir := $(abspath ..)/target-design/chipyard
rocketchip_dir := $(chipyard_dir)/generators/rocket-chip

# Scala invocation options
JVM_MEMORY ?= 16G
SCALA_VERSION ?= 2.12.10
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
export JAVA_TOOL_OPTIONS ?= -Xmx$(JVM_MEMORY) -Xss8M -Dsbt.supershell=false -Djava.io.tmpdir=$(base_dir)/.java_tmp

sbt_sources = $(shell find -L $(base_dir) -name target -prune -o -iname "*.sbt" -print 2> /dev/null)
SCALA_BUILDTOOL_DEPS ?= $(sbt_sources)

SBT_THIN_CLIENT_TIMESTAMP = $(base_dir)/project/target/active.json

ifdef ENABLE_SBT_THIN_CLIENT
override SCALA_BUILDTOOL_DEPS += $(SBT_THIN_CLIENT_TIMESTAMP)
# enabling speeds up sbt loading
SBT_CLIENT_FLAG = --client
endif

# Use java -jar approach by default so that SBT thin-client sees the JAVA flags
# Workaround for behavior reported here: https://github.com/sbt/sbt/issues/6468
SBT_BIN ?= java -jar $(rocketchip_dir)/sbt-launch.jar
SBT = $(SBT_BIN) $(SBT_CLIENT_FLAG)

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

else # FIRESIM_STANDALONE

# Chipyard make variables
base_dir := $(abspath ../../..)
sim_dir := $(firesim_base_dir)
chipyard_dir := $(base_dir)
include $(base_dir)/variables.mk
include $(base_dir)/common.mk

endif

ifdef FIRESIM_STANDALONE
	firesim_sbt_project := firesim
else
	firesim_sbt_project := {file:${firesim_base_dir}/}firesim
endif

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
