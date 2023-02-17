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

SBT_COMMAND ?= shell
SBT_NON_THIN ?= $(subst $(SBT_CLIENT_FLAG),,$(SBT))
.PHONY: sbt
sbt:
	cd $(base_dir) && $(SBT_NON_THIN) ";project $(FIRESIM_SBT_PROJECT); $(SBT_COMMAND)"

################################################################################
# Target Configuration
################################################################################

# Target project containing the generator of the design.
TARGET_SBT_PROJECT ?= $(FIRESIM_SBT_PROJECT)

# If the target project is not the implicit FireSim one, this definition must
# enumerate all directories containing the sources of the generator.
TARGET_SOURCE_DIRS ?=

################################################################################
# Build jars using SBT assembly and cache them.
################################################################################

# Helper to build a classpath for a project. This first builds the project
# while showing error messages, then it runs sbt again with errors disabled to
# capture the classpath from the output (errors are dumped to stdout otherwise).
define build_classpath
	$(SBT_NON_THIN) \
		--error \
		"set showSuccess := false; project $(1); compile; package; export $(2):fullClasspath" \
		| head -n 1 | tr -d '\n' > $@
endef

# Helpers to identify all source files of the FireSim project.
find_sources_in_dir = $(shell \
	find -L $(1)/ -name target -prune -o -iname "[!.]*.scala" -print \
	2> /dev/null \
	| grep -E $(2) \
)

firesim_source_dirs = \
	$(addprefix $(firesim_base_dir)/,\
			. \
			midas \
			midas/targetutils \
			firesim-lib \
			rocket-chip/src \
			rocket-chip/api-config-chipsalliance \
	)
firesim_main_srcs = $(foreach dir, $(firesim_source_dirs), \
	$(call find_sources_in_dir, $(dir), 'src/main/scala'))
firesim_test_srcs = $(foreach dir, $(firesim_source_dirs), \
	$(call find_sources_in_dir, $(dir), 'src/test/scala'))

# Dummy rule building a token file which compiles all scala sources of the main
# FireSim project. This ensures that SBT is invoked once in parallel builds.
$(BUILD_DIR)/firesim.build: $(SCALA_BUILDTOOL_DEPS) $(firesim_main_srcs) $(firesim_test_srcs)
	@mkdir -p $(@D)
	$(SBT_NON_THIN) "set showSuccess := false; project $(FIRESIM_SBT_PROJECT); compile; package"
	@touch $@


FIRESIM_MAIN_CP := $(BUILD_DIR)/firesim-main.classpath
$(FIRESIM_MAIN_CP): $(BUILD_DIR)/firesim.build
	@mkdir -p $(@D)
	cd $(base_dir) && $(call build_classpath,$(FIRESIM_SBT_PROJECT),runtime)


FIRESIM_TEST_CP := $(BUILD_DIR)/firesim-test.classpath
$(FIRESIM_TEST_CP): $(BUILD_DIR)/firesim.build
	@mkdir -p $(@D)
	cd $(base_dir) && $(call build_classpath,$(FIRESIM_SBT_PROJECT),test)

# If the target project is the main FireSim project, provide the test classpath
# as it defines the target configs and parameters for designs to elaborate.
ifneq ($(FIRESIM_SBT_PROJECT),$(TARGET_SBT_PROJECT))

target_srcs = $(foreach dir,$(TARGET_SOURCE_DIRS), \
	$(call find_sources_in_dir, $(dir), 'src/main/scala'))

$(BUILD_DIR)/target.build: $(BUILD_DIR)/firesim.build $(target_srcs)
	@mkdir -p $(@D)
	$(SBT_NON_THIN) "set showSuccess := false; project $(TARGET_SBT_PROJECT); compile; package"
	@touch $@

TARGET_CP := $(BUILD_DIR)/target.classpath
$(TARGET_CP): $(BUILD_DIR)/target.build
	@mkdir -p $(@D)
	cd $(base_dir) && $(call build_classpath,$(TARGET_SBT_PROJECT),runtime)

else

TARGET_CP := $(FIRESIM_TEST_CP)

endif

.PHONY: firesim-main-classpath firesim-test-classpath target-classpath
firesim-main-classpath: $(FIRESIM_MAIN_CP)
firesim-test-classpath: $(FIRESIM_TEST_CP)
target-classpath: $(TARGET_CP)

################################################################################
# Runners for tests.
################################################################################

.PHONY: test
test: $(FIRESIM_MAIN_CP) $(FIRESIM_TEST_CP) $(TARGET_CP)
	cd $(base_dir) && $(SBT_NON_THIN) ";project $(FIRESIM_SBT_PROJECT); test"

.PHONY: testOnly
testOnly: $(FIRESIM_MAIN_CP) $(FIRESIM_TEST_CP) $(TARGET_CP)
	cd $(base_dir) && java -cp $$(cat $(FIRESIM_TEST_CP)) org.scalatest.run $(SCALA_TEST)

################################################################################
# ScalaDoc
################################################################################

.PHONY: scaladoc
scaladoc:
	cd $(base_dir) && $(SBT) "project {file:$(firesim_base_dir)}firesim" "unidoc"
