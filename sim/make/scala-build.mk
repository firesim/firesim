# See LICENSE for license details.

################################################################################
#
# This file defines rules to build the Scala components of FireSim.
#
# The file provides 3 targets caching the classpath containing compiled jars
# to be used by subsequent java invocations, fully bypassing the slow sbt setup
# on repeated invocations to SBT.
#
# The 3 cached classpaths are:
#
#  FIRESIM_MAIN_CP: contains the GoldenGate entry point.
#
#  FIRESIM_TEST_CP: contains generators and configurations for most tests.
#
#  TARGET_CP: contains the entry point of the target Chisel generator.
#             It is built out of the TARGET_SBT_PROJECT. TARGET_SOURCE_DIRS
#             must enumerate all the directories containing the scala sources
#             to compile this target.
#
# Any rule containing a Java invocation must add as a dependency one of these
# classpaths. Corresponding SBT invocations still work (substitute the classpath
# with the correct project).
#
################################################################################

################################################################################
# Helper to run SBT in the project.
################################################################################

# Scala invocation options
JAVA_HEAP_SIZE ?= 16G
# Disable the SBT supershell as interacts poorly with scalatest output and breaks
# the runtime config generator.
export JAVA_TOOL_OPTIONS ?= -Xmx$(JAVA_HEAP_SIZE) -Xss8M -Djava.io.tmpdir=$(firesim_base_dir)/.java_tmp
export SBT_OPTS ?= -Dsbt.ivy.home=$(firesim_base_dir)/.ivy2 -Dsbt.global.base=$(firesim_base_dir)/.sbt -Dsbt.boot.directory=$(firesim_base_dir)/.sbt/boot/ -Dsbt.color=always -Dsbt.supershell=false -Dsbt.server.forcestart=true
SBT ?= java -jar $(firesim_base_dir)/sbt-launch.jar $(SBT_OPTS)

# (1) - directory of the build.sbt used to compile the fat jar
# (2) - classpath of the fat jar
# (3) - main class
# (4) - main class arguments
define run_jar_scala_main
	cd $(1) && java -cp $(2) $(3) $(4)
endef

# (1) - build.sbt path
# (2) - sbt project
# (3) - main class
# (4) - main class arguments
define run_scala_main
	cd $(1) && $(SBT) ";project $(2); runMain $(3) $(4)"
endef

# (1) - build.sbt path
# (2) - sbt project to assemble
# (3) - classpath file(s) to create
define run_sbt_assembly
	cd $(1) && $(SBT) ";project $(2); set assembly / assemblyOutputPath := file(\"$(3)\"); assembly" && touch $(3)
endef

################################################################################
# Misc. Default Configuration and Commands
################################################################################

# sbt project in sim/ directory
FIRESIM_SBT_PROJECT ?= firesim
firesim_source_dirs = \
	$(addprefix $(firesim_base_dir)/,\
			src \
			midas \
			midas/targetutils \
			firesim-lib \
	)

SBT_COMMAND ?= shell
.PHONY: sbt
sbt:
	cd $(firesim_base_dir) && $(SBT) ";project $(FIRESIM_SBT_PROJECT); $(SBT_COMMAND)"

################################################################################
# Target Configuration
################################################################################

# Target SBT project containing the generator of the design.
TARGET_SBT_PROJECT ?= $(FIRESIM_SBT_PROJECT)

# Directory to invoke SBT in (directory contains target SBT project).
TARGET_SBT_DIR ?= $(firesim_base_dir)

# All directories containing the sources of the generator.
TARGET_SOURCE_DIRS ?= $(firesim_source_dirs)

################################################################################
# Build jars using SBT assembly and cache them.
################################################################################

# Returns a list of files in directories $1 with single file extension $2 that have $3 in the name.
# Only works if $(1) is a valid string to prevent a large/long-running find command.
fs_lookup_srcs = $(if $(strip $(1)), $(shell find -L $(1) -name target -prune -o \( -iname "*.$(strip $(2))" ! -iname ".*" \) -print 2> /dev/null | grep -E $(3)))

# Returns a list of files in directories $1 with *any* of the file extensions in $3 that have $2 in the name
fs_lookup_srcs_by_multiple_type = $(foreach type,$(3),$(call fs_lookup_srcs,$(1),$(type),$(2)))

SCALA_EXT = scala
VLOG_EXT = sv v

#### main classpath always built ####

firesim_main_srcs = \
	$(call fs_lookup_srcs_by_multiple_type, $(firesim_source_dirs), 'main/scala', $(SCALA_EXT)) \
	$(call fs_lookup_srcs_by_multiple_type, $(firesim_source_dirs), 'main/resources', $(VLOG_EXT))
firesim_test_srcs = \
	$(call fs_lookup_srcs_by_multiple_type, $(firesim_source_dirs), 'test/scala', $(SCALA_EXT)) \
	$(call fs_lookup_srcs_by_multiple_type, $(firesim_source_dirs), 'test/resources', $(VLOG_EXT))

FIRESIM_MAIN_CP := $(BUILD_DIR)/firesim-main.jar
# if *_CLASSPATH is a true java classpath, it can be colon-delimited list of paths (on *nix)
FIRESIM_MAIN_CP_TARGETS := $(subst :, ,$(FIRESIM_MAIN_CP))
$(FIRESIM_MAIN_CP): $(SCALA_BUILDTOOL_DEPS) $(firesim_main_srcs) $(firesim_test_srcs)
	@mkdir -p $(@D)
	$(call run_sbt_assembly,$(firesim_base_dir),$(FIRESIM_SBT_PROJECT),$(FIRESIM_MAIN_CP))

.PHONY: firesim-main-classpath
firesim-main-classpath: $(FIRESIM_MAIN_CP)

#### target classpath only built if project makefrag asks for it ####

# if any of the defaults changed, then generate a unique target classpath.
# otherwise use the firesim main classpath.
# note: this is ugly but quickly does 'if A && B && C'
ifneq ($(TARGET_SBT_PROJECT).$(TARGET_SBT_DIR).$(TARGET_SOURCE_DIRS),$(FIRESIM_SBT_PROJECT).$(firesim_base_dir).$(firesim_source_dirs))
TARGET_CP := $(BUILD_DIR)/target.jar
target_srcs = \
	$(call fs_lookup_srcs_by_multiple_type, $(TARGET_SOURCE_DIRS), 'src/main/scala', $(SCALA_EXT)) \
	$(call fs_lookup_srcs_by_multiple_type, $(TARGET_SOURCE_DIRS), 'src/main/resources', $(VLOG_EXT))
# if *_CLASSPATH is a true java classpath, it can be colon-delimited list of paths (on *nix)
TARGET_CP_TARGETS ?= $(subst :, ,$(TARGET_CP))
$(TARGET_CP): $(target_srcs) | $(FIRESIM_MAIN_CP)
	@mkdir -p $(@D)
	$(call run_sbt_assembly,$(TARGET_SBT_DIR),$(TARGET_SBT_PROJECT),$(TARGET_CP))
else
TARGET_CP := $(FIRESIM_MAIN_CP)
endif

.PHONY: target-classpath
target-classpath: $(TARGET_CP)

################################################################################
# Runners for tests.
################################################################################

.PHONY: test
test:
	cd $(firesim_base_dir) && $(SBT) ";project $(FIRESIM_SBT_PROJECT); test"

.PHONY: testOnly
testOnly:
	cd $(firesim_base_dir) && $(SBT) ";project $(FIRESIM_SBT_PROJECT); testOnly $(SCALA_TEST)"

################################################################################
# ScalaDoc
################################################################################

.PHONY: scaladoc
scaladoc:
	cd $(firesim_base_dir) && $(SBT) ";project $(FIRESIM_SBT_PROJECT); unidoc"
