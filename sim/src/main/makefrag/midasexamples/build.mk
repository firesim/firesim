# See LICENSE for license details.

##########################
# RTL Generation         #
##########################

chisel_src_dirs = \
		$(addprefix $(firesim_base_dir)/,. midas midas/targetutils firesim-lib) \
		$(addprefix $(chipyard_dir)/generators/, rocket-chip/src, rocket-chip/api-config-chipsalliance)

chisel_srcs = $(foreach submodule,$(chisel_src_dirs),\
	$(shell find $(submodule)/ -iname "[!.]*.scala" -print 2> /dev/null | grep 'src/main/scala'))


# Rocket Chip stage requires a fully qualified classname for each fragment, whereas Chipyard's does not.
# This retains a consistent TARGET_CONFIG naming convention across the different target projects.
subst_prefix=,$(TARGET_CONFIG_PACKAGE).

$(FIRRTL_FILE) $(ANNO_FILE): $(chisel_srcs) $(FIRRTL_JAR) $(SCALA_BUILDTOOL_DEPS)
	mkdir -p $(@D)
	$(call run_scala_main,$(firesim_sbt_project),freechips.rocketchip.system.Generator, \
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--configs $(TARGET_CONFIG_PACKAGE).$(subst _,$(subst_prefix),$(TARGET_CONFIG)))
