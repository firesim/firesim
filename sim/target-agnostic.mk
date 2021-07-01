# See LICENSE for license details.

# FireSim MAKEFRAG interface - Compulsory variables follow
# The Generator CLASSPATH used in java invocation to run generator and goldengate. Can be:
#  - FAT JAR created using sbt assembly
#  - An actual Java classpath created some other way
GEN_CLASSPATH ?=
# The directory into which generated verilog and headers will be dumped
# RTL simulations will also be built here
GENERATED_DIR ?=
# Results from RTL simulations live here
OUTPUT_DIR ?=
# Root name for generated binaries
DESIGN ?=

# SOURCE FILES
# Driver source files
DRIVER_CC ?=
DRIVER_H ?=
# Simulation memory map emitted by the MIDAS compiler
HEADER ?=
# The midas-generated simulator RTL which will be baked into the FPGA shell project
VERILOG ?=

# The target's FIRRTL and associated anotations
FIRRTL_FILE ?=
ANNO_FILE ?=

# The host config package and class string
PLATFORM_CONFIG_PACKAGE ?= firesim.midasexamples
PLATFORM_CONFIG ?= DefaultF1Config

# The name of the generated runtime configuration file
CONF_NAME ?= runtime.conf

# The host platform type
PLATFORM ?= f1

# Target-specific CXX and LD flags
TARGET_CXX_FLAGS ?=
TARGET_LD_FLAGS ?=

simif_dir = $(firesim_base_dir)/midas/src/main/cc
midas_h  = $(shell find $(simif_dir) -name "*.h")
midas_cc = $(shell find $(simif_dir) -name "*.cc")

common_cxx_flags := $(TARGET_CXX_FLAGS) -Wno-unused-variable
common_ld_flags := $(TARGET_LD_FLAGS) -lrt

####################################
# Golden Gate Invocation           #
####################################
firesim_root_sbt_project := {file:$(firesim_base_dir)}firesim
# Pre-simulation-mapping annotations which includes all Bridge Annotations
# extracted used to generate new runtime configurations.
fame_annos := $(GENERATED_DIR)/post-bridge-extraction.json

$(GENERATED_DIR):
	mkdir -p $@

# empty recipe to help make understand multiple targets come from single recipe invocation
# without using the new (4.3) '&:' grouped targets see https://stackoverflow.com/a/41710495
$(VERILOG) $(HEADER) $(fame_annos): $(VERILOG).intermediate ;

# Disable FIRRTL 1.4 deduplication because it creates multiple failures
# Run the 1.3 version instead (checked-in). If dedup must be completely disabled,
# pass --no-legacy-dedup as well
.SECONDARY: $(VERILOG).intermediate
$(VERILOG).intermediate: $(FIRRTL_FILE) $(ANNO_FILE) $(PRE_ELABORATION_TARGETS)
	$(call run_main,$(firesim_sbt_project),midas.stage.GoldenGateMain,\
		-o $(VERILOG) -i $(FIRRTL_FILE) -td $(GENERATED_DIR) \
		-faf $(ANNO_FILE) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		--no-dedup \
		-E verilog \
	)
	grep -sh ^ $(GENERATED_DIR)/firrtl_black_box_resource_files.f | \
	xargs cat >> $(VERILOG) # Append blackboxes to FPGA wrapper, if any

####################################
# Runtime-Configuration Generation #
####################################

# This reads in the annotations from a generated target, elaborates a
# FASEDTimingModel if a BridgeAnnoation for one exists, and asks for user input
# to generate a runtime configuration that is compatible with the generated
# hardware (BridgeModule). Useful for modelling a memory system that differs from the default.
.PHONY: conf
conf: $(fame_annos) $(subst :, ,$(GEN_CLASSPATH))
	mkdir -p $(GENERATED_DIR)
	cd $(base_dir) && java $(JAVA_OPTS) -cp $(GEN_CLASSPATH) midas.stage.RuntimeConfigGeneratorMain \
		-td $(GENERATED_DIR) \
		-faf $(fame_annos) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		-ggrc $(CONF_NAME)

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_CXXOPTS ?= -O0
VERILATOR_MAKEFLAGS ?= -j8 VM_PARALLEL_BUILDS=1

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS += $(common_cxx_flags) $(VERILATOR_CXXOPTS) -D RTLSIM
$(verilator) $(verilator_debug): export LDFLAGS += $(common_ld_flags)

$(verilator): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(VERILOG)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir) VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"

$(verilator_debug): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(VERILOG)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir) VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"

.PHONY: verilator verilator-debug
verilator: $(verilator)
verilator-debug: $(verilator_debug)

##############################
# VCS MIDAS-Level Simulators #
##############################

VCS_CXXOPTS ?= -O2

vcs = $(GENERATED_DIR)/$(DESIGN)
vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug

$(vcs) $(vcs_debug): export CXXFLAGS += $(common_cxx_flags) $(VCS_CXXOPTS) -I$(VCS_HOME)/include -D RTLSIM
$(vcs) $(vcs_debug): export LDFLAGS += $(common_ld_flags)

$(vcs): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(VERILOG)
	$(MAKE) -C $(simif_dir) vcs PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

$(vcs_debug): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(VERILOG)
	$(MAKE) -C $(simif_dir) vcs-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

.PHONY: vcs vcs-debug
vcs: $(vcs)
vcs-debug: $(vcs_debug)

############################
# Master Simulation Driver #
############################
DRIVER_CXXOPTS ?= -O2

$(OUTPUT_DIR)/$(DESIGN).chain: $(VERILOG)
	mkdir -p $(OUTPUT_DIR)
	$(if $(wildcard $(GENERATED_DIR)/$(DESIGN).chain),cp $(GENERATED_DIR)/$(DESIGN).chain $@,)

$(PLATFORM) = $(OUTPUT_DIR)/$(DESIGN)-$(PLATFORM)
$(PLATFORM): $($(PLATFORM)) $(OUTPUT_DIR)/$(DESIGN).chain

fpga_dir = $(firesim_base_dir)/../platforms/$(PLATFORM)/aws-fpga

$(f1): export CXXFLAGS += $(common_cxx_flags) $(DRIVER_CXXOPTS) -I$(fpga_dir)/sdk/userspace/include
# Statically link libfesvr to make it easier to distribute drivers to f1 instances
$(f1): export LDFLAGS += $(common_ld_flags) -lfpga_mgmt

# Compile Driver
$(f1): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(HEADER) $(OUTPUT_DIR)/build/
	cp -f $(GENERATED_DIR)/$(CONF_NAME) $(OUTPUT_DIR)/runtime.conf
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(OUTPUT_DIR)/build OUT_DIR=$(OUTPUT_DIR) DRIVER="$(DRIVER_CC)" \
	TOP_DIR=$(chipyard_dir)

#############################
# FPGA Build Initialization #
#############################
board_dir 	   := $(fpga_dir)/hdk/cl/developer_designs
fpga_work_dir  := $(board_dir)/cl_$(name_tuple)
fpga_build_dir := $(fpga_work_dir)/build
verif_dir      := $(fpga_work_dir)/verif
fpga_v         := $(fpga_work_dir)/design/cl_firesim_generated.sv
ila_work_dir   := $(fpga_work_dir)/design/ila_files/
fpga_vh        := $(fpga_work_dir)/design/cl_firesim_generated_defines.vh
fpga_tcl_env   := $(fpga_work_dir)/design/cl_firesim_generated_env.tcl
repo_state     := $(fpga_work_dir)/design/repo_state
cl_firesim_files := $(shell find $(board_dir)/cl_firesim -name '*')

#  ensures that the replace-rtl.sh will be generated including the recipe for $(fpga_work_dir)/stamp.  
#  This is needed because  distributed-elaboration mode of 'firesim buildafi' assumes that it will 
#  rsync $(board_dir)/cl_firesim to build host and replace-rtl.sh is responsible for creating $(fpga_work_dir) from the template.
PRE_ELABORATION_TARGETS += $(firstword $(cl_firesim_files))

# don't search for implicit rules to make the cl_firesim_files (reduces useless output in --debug=i)
$(cl_firesim_files): ;

$(fpga_work_dir)/stamp: $(cl_firesim_files)
	mkdir -p $(@D)
	cp -rf $(board_dir)/cl_firesim -T $(fpga_work_dir)
	touch $@

$(fpga_v): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/repo_state $(repo_state) || $(firesim_base_dir)/../scripts/repo_state_summary.sh > $(repo_state)
	cp -f $< $@
	sed -i "s/\$$random/64'b0/g" $@
	sed -i "s/\(^ *\)fatal;\( *$$\)/\1fatal(0, \"\");\2/g" $@

$(fpga_vh): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

$(fpga_tcl_env): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

.PHONY: $(ila_work_dir)
$(ila_work_dir): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/firesim_ila_insert_* $(fpga_work_dir)/design/ila_files/
	sed -i "s/\$$random/64'b0/g" $(fpga_work_dir)/design/ila_files/*
	sed -i "s/\(^ *\)fatal;\( *$$\)/\1fatal(0, \"\");\2/g" $(fpga_work_dir)/design/ila_files/*

# Goes as far as setting up the build directory without running the cad job
# Used by the manager before passing a build to a remote machine
replace-rtl: $(fpga_v) $(ila_work_dir) $(fpga_vh) $(fpga_tcl_env)

.PHONY: replace-rtl

$(firesim_base_dir)/scripts/checkpoints/$(name_tuple): $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	ln -sf $(fpga_build_dir)/checkpoints/to_aws $@

# Runs a local fpga-bitstream build. Strongly consider using the manager instead.
fpga: export CL_DIR := $(fpga_work_dir)
fpga: $(fpga_v) $(base_dir)/scripts/checkpoints/$(name_tuple)
	cd $(fpga_build_dir)/scripts && ./aws_build_dcp_from_cl.sh -notify

# Generate a script that can be run remotely on a build host by manager assuming only the following
# are copied (recursively with Fabric's rsync_project) to the build-host:
# - $(GEN_CLASSPATH)
# - $(GENERATED_DIR)
# - $(board_dir)/cl_firesim
# The script will be run with 'bash -xe' to approximate the behavior of make shell execution
#  --no-print-directory avoids some info print statements that aren't actual commands
#  --assume-new=$(PRE_ELABORATION_TARGETS) ensures that the script consistently generates the commands for
#  $(PRE_ELABORATION_TARGETS) -> replace-rtl, regardless of whether they need to be done.  This is done so that
#  replace-rtl.sh should always be legit commands and we don't have to handle shortcuts for output
#  that look like "make: Nothing to be done for `replace-rtl'."
.PHONY: gen-replace-rtl-script
gen-replace-rtl-script: $(PRE_ELABORATION_TARGETS) | $(GENERATED_DIR)
ifdef ENABLE_SBT_THIN_CLIENT
	@echo "Use of ENABLE_SBT_THIN_CLIENT is inconsistent with the purpose of 'gen-replace-rtl-script'. Aborting."
	@exit 1
else
	$(MAKE) replace-rtl -n  --no-print-directory $(addprefix --assume-new=, $(PRE_ELABORATION_TARGETS)) > $(GENERATED_DIR)/replace-rtl.sh
	$(firesim_base_dir)/../scripts/repo_state_summary.sh > $(GENERATED_DIR)/repo_state
endif


#############################
# FPGA-level RTL Simulation #
#############################

# Run XSIM DUT
.PHONY: xsim-dut
xsim-dut: replace-rtl $(fpga_work_dir)/stamp
	cd $(verif_dir)/scripts && $(MAKE) C_TEST=test_firesim

# Compile XSIM Driver #
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

$(xsim): export CXXFLAGS += $(common_cxx_flags) -D SIMULATION_XSIM -D NO_MAIN
$(xsim): export LDFLAGS += $(common_ld_flags)
$(xsim): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) OUT_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" \
	TOP_DIR=$(chipyard_dir)

.PHONY: xsim
xsim: $(xsim)

#########################
# MIDAS Unit Tests      #
#########################
UNITTEST_CONFIG ?= AllUnitTests

rocketchip_dir := $(chipyard_dir)/generators/rocket-chip
unittest_generated_dir := $(base_dir)/generated-src/unittests/$(UNITTEST_CONFIG)
unittest_args = \
		BASE_DIR=$(base_dir) \
		EMUL=$(EMUL) \
		ROCKETCHIP_DIR=$(rocketchip_dir) \
		GEN_DIR=$(unittest_generated_dir) \
		SBT="$(SBT)" \
		SBT_PROJECT=$(firesim_root_sbt_project) \
		CONFIG=$(UNITTEST_CONFIG) \
		TOP_DIR=$(chipyard_dir)

.PHONY:compile-midas-unittests
compile-midas-unittests:
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $(unittest_args)

.PHONY:run-midas-unittests
run-midas-unittests:
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

.PHONY:run-midas-unittests-debug
run-midas-unittests-debug:
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

#########################
# ScalaDoc              #
#########################
scaladoc:
	cd $(base_dir) && $(SBT) "project {file:$(firesim_base_dir)}firesim" "unidoc"

.PHONY: scaladoc
#########################
# Cleaning Recipes      #
#########################
cleanfpga:
	rm -rf $(fpga_work_dir)

mostlyclean:
	rm -rf $(verilator) $(verilator_debug) $(vcs) $(vcs_debug) $($(PLATFORM)) $(OUTPUT_DIR)

clean:
	rm -rf $(GENERATED_DIR) $(OUTPUT_DIR)

veryclean:
	rm -rf generated-src output

tags: $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .

.PHONY: default verilog compile
.PHONY: $(PLATFORM)-driver fpga
.PHONY: mostlyclean clean

.PRECIOUS: $(OUTPUT_DIR)/%.vpd $(OUTPUT_DIR)/%.out $(OUTPUT_DIR)/%.run

# Remove all implicit suffix rules; This improves make performance substantially as it no longer
# attempts to resolve implicit rules on 1000+ scala files.
.SUFFIXES:
