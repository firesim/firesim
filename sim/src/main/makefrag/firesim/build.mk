# See LICENSE for license details.

##################
# RTL Generation #
##################
ifdef FIRESIM_STANDALONE
	target_sbt_project := {file:${chipyard_dir}}firechip

	lookup_scala_srcs = $(shell find -L $(1)/ -name target -prune -o -iname "[!.]*.scala" -print 2> /dev/null)
	SOURCE_DIRS = $(chipyard_dir)/generators $(firesim_base_dir)
	SCALA_SOURCES = $(call lookup_scala_srcs,$(SOURCE_DIRS))
else
	target_sbt_project := firechip
endif

$(FIRRTL_FILE) $(ANNO_FILE): $(SCALA_SOURCES) $(FIRRTL_JAR) $(SCALA_BUILDTOOL_DEPS)
	mkdir -p $(@D)
	$(call run_scala_main,$(target_sbt_project),chipyard.Generator,\
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--legacy-configs $(TARGET_CONFIG_PACKAGE):$(TARGET_CONFIG))
