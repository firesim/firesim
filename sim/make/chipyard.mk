# See LICENSE for license details.

ifdef FIRESIM_STANDALONE

base_dir := $(firesim_base_dir)
chipyard_dir := $(abspath ..)/target-design/chipyard
rocketchip_dir := $(chipyard_dir)/generators/rocket-chip

# Scala invocation options
JAVA_HEAP_SIZE ?= 16G
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
export JAVA_TOOL_OPTIONS ?= -Xmx$(JAVA_HEAP_SIZE) -Xss8M -Djava.io.tmpdir=$(base_dir)/.java_tmp
export SBT_OPTS ?= -Dsbt.ivy.home=$(base_dir)/.ivy2 -Dsbt.global.base=$(base_dir)/.sbt -Dsbt.boot.directory=$(base_dir)/.sbt/boot/ -Dsbt.color=always -Dsbt.supershell=false -Dsbt.server.forcestart=true

sbt_sources = $(shell find -L $(base_dir) -name target -prune -o -iname "*.sbt" -print 2> /dev/null)
SCALA_BUILDTOOL_DEPS ?= $(sbt_sources)

SBT ?= java -jar $(chipyard_dir)/scripts/sbt-launch.jar $(SBT_OPTS)

# (1) - classpath of the fat jar
# (2) - main class
# (3) - main class arguments
define run_jar_scala_main
	cd $(base_dir) && java -cp $(1) $(2) $(3)
endef

# (1) - sbt project
# (2) - main class
# (3) - main class arguments
define run_scala_main
	cd $(base_dir) && $(SBT) ";project $(1); runMain $(2) $(3)"
endef

# (1) - sbt project to assemble
# (2) - classpath file(s) to create
define run_sbt_assembly
	cd $(base_dir) && $(SBT) ";project $(1); set assembly / assemblyOutputPath := file(\"$(2)\"); assembly" && touch $(2)
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
