# See LICENSE for license details.

##########################
# RTL Generation         #
##########################

chisel_src_dirs = \
		$(addprefix $(firesim_base_dir)/,. midas midas/targetutils firesim-lib) \
		$(addprefix $(chipyard_dir)/generators/, rocket-chip/src, rocket-chip/api-config-chipsalliance)

chisel_srcs = $(foreach submodule,$(chisel_src_dirs),\
	$(shell find $(submodule)/ -iname "[!.]*.scala" -print 2> /dev/null | grep 'src/main/scala'))

SIM_RUNTIME_CONF ?= $(GENERATED_DIR)/$(CONF_NAME)
mem_model_args = $(shell cat $(SIM_RUNTIME_CONF))
COMMON_SIM_ARGS ?= $(mem_model_args)
vcs_args = +vcs+initreg+0 +vcs+initmem+0

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

#######################################
# Setup Extra Verilator Compile Flags #
#######################################

## default flags added for cva6
CVA6_VERILATOR_FLAGS = \
	--unroll-count 256 \
	-Werror-PINMISSING \
	-Werror-IMPLICIT \
	-Wno-fatal \
	-Wno-PINCONNECTEMPTY \
	-Wno-ASSIGNDLY \
	-Wno-DECLFILENAME \
	-Wno-UNUSED \
	-Wno-UNOPTFLAT \
	-Wno-BLKANDNBLK \
	-Wno-style \
	-Wall

# normal flags used for midas builds (that are incompatible with cva6)
DEFAULT_MIDAS_VERILATOR_FLAGS = \
	--assert

# AJG: this must be evaluated after verilog generation to work (hence the =)
EXTRA_VERILATOR_FLAGS = \
	$(shell if ! grep -iq "module.*cva6" $(simulator_verilog); then echo "$(DEFAULT_MIDAS_VERILATOR_FLAGS)"; else echo "$(CVA6_VERILATOR_FLAGS)"; fi)
