# See LICENSE for license details.

# FireSim Target Agnostic Make Fragment
#
# Defines make targets for:
# - invoking Golden Gate (phony: verilog / compile)
# - building a simulation driver (phony: f1)
# - populating an FPGA build directory (phony: replace-rtl)
# - generating new runtime configurations (phony: conf)
# - compiling meta-simulators (phony: verilator, vcs, verilator-debug, vcs-debug)
#

# The prefix used for all Golden Gate-generated files
BASE_FILE_NAME ?=

# The directory into which generated verilog and headers will be dumped
# RTL simulations will also be built here
GENERATED_DIR ?=
# Results from RTL simulations live here
OUTPUT_DIR ?=
# Root name for generated binaries
DESIGN ?=

# The target's FIRRTL and associated anotations; inputs to Golden Gate
FIRRTL_FILE ?=
ANNO_FILE ?=

# The host config package and class string
PLATFORM_CONFIG_PACKAGE ?= firesim.midasexamples
PLATFORM_CONFIG ?= DefaultF1Config

# The name of the generated runtime configuration file
CONF_NAME ?= $(BASE_FILE_NAME).runtime.conf

# The host platform type, currently only f1 is supported
PLATFORM ?=

# Driver source files
DRIVER_CC ?=
DRIVER_H ?=

# Target-specific CXX and LD flags for compiling the driver and meta-simulators
# These should be platform independent should be governed by the target-specific makefrag
TARGET_CXX_FLAGS ?=
TARGET_LD_FLAGS ?=

# END MAKEFRAG INTERFACE

# Defined for each platform
platforms_dir := $(abspath $(firesim_base_dir)/../platforms)

simif_dir = $(firesim_base_dir)/midas/src/main/cc
midas_h  = $(shell find $(simif_dir) -name "*.h")
midas_cc = $(shell find $(simif_dir) -name "*.cc")

common_cxx_flags := $(TARGET_CXX_FLAGS) -Wno-unused-variable
common_ld_flags := $(TARGET_LD_FLAGS) -lrt

# Simulation memory map emitted by the MIDAS compiler
header := $(GENERATED_DIR)/$(BASE_FILE_NAME).const.h
# The midas-generated simulator RTL which will be baked into the FPGA shell project
simulator_verilog := $(GENERATED_DIR)/$(BASE_FILE_NAME).sv

####################################
# Golden Gate Invocation           #
####################################
firesim_root_sbt_project := {file:$(firesim_base_dir)}firesim
# Pre-simulation-mapping annotations which includes all Bridge Annotations
# extracted used to generate new runtime configurations.
fame_annos := $(GENERATED_DIR)/post-bridge-extraction.json

.PHONY: verilog compile
verilog: $(simulator_verilog)
compile: $(simulator_verilog)

# empty recipe to help make understand multiple targets come from single recipe invocation
# without using the new (4.3) '&:' grouped targets see https://stackoverflow.com/a/41710495
.SECONDARY: $(simulator_verilog).intermediate
$(simulator_verilog) $(header) $(fame_annos): $(simulator_verilog).intermediate ;

# Disable FIRRTL 1.4 deduplication because it creates multiple failures
# Run the 1.3 version instead (checked-in). If dedup must be completely disabled,
# pass --no-legacy-dedup as well
$(simulator_verilog).intermediate: $(FIRRTL_FILE) $(ANNO_FILE) $(SCALA_BUILDTOOL_DEPS)
	$(call run_scala_main,$(firesim_sbt_project),midas.stage.GoldenGateMain,\
		-i $(FIRRTL_FILE) \
		-td $(GENERATED_DIR) \
		-faf $(ANNO_FILE) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		--output-filename-base $(BASE_FILE_NAME) \
		--no-dedup \
	)
	grep -sh ^ $(GENERATED_DIR)/firrtl_black_box_resource_files.f | \
	xargs cat >> $(simulator_verilog) # Append blackboxes to FPGA wrapper, if any

####################################
# Runtime-Configuration Generation #
####################################

# This reads in the annotations from a generated target, elaborates a
# FASEDTimingModel if a BridgeAnnoation for one exists, and asks for user input
# to generate a runtime configuration that is compatible with the generated
# hardware (BridgeModule). Useful for modelling a memory system that differs from the default.
.PHONY: conf
conf: $(fame_annos)
	mkdir -p $(GENERATED_DIR)
	cd $(base_dir) && $(SBT) "project $(firesim_sbt_project)" "runMain midas.stage.RuntimeConfigGeneratorMain \
		-td $(GENERATED_DIR) \
		-faf $(fame_annos) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		-ggrc $(CONF_NAME)"

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_CXXOPTS ?= -O0
VERILATOR_MAKEFLAGS ?= -j8 VM_PARALLEL_BUILDS=1

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VERILATOR_CXXOPTS) -D RTLSIM
$(verilator) $(verilator_debug): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

$(verilator): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir) VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"

$(verilator_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator-debug PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
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

$(vcs) $(vcs_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VCS_CXXOPTS) -I$(VCS_HOME)/include -D RTLSIM
# VCS can mangle the ordering of libraries and object files at link time such
# that some valid dependencies are pruned when --as-needed is set.
# Conservatively set --no-as-needed in case --as-needed is defined in LDFLAGS.
$(vcs) $(vcs_debug): export LDFLAGS := $(LDFLAGS) -Wl,--no-as-needed $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

$(vcs): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) -C $(simif_dir) vcs PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

$(vcs_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) -C $(simif_dir) vcs-debug PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

.PHONY: vcs vcs-debug
vcs: $(vcs)
vcs-debug: $(vcs_debug)

############################
# Master Simulation Driver #
############################
DRIVER_CXXOPTS ?= -O2

$(PLATFORM) = $(OUTPUT_DIR)/$(DESIGN)-$(PLATFORM)
$(PLATFORM): $($(PLATFORM))

.PHONY: driver
driver: $(PLATFORM)

$(f1): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-I$(platforms_dir)/f1/aws-fpga/sdk/userspace/include
# We will copy shared libs into same directory as driver on runhost, so add $ORIGIN to rpath
$(f1): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' -L /usr/local/lib64 -lfpga_mgmt

# Compile Driver
$(f1): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(OUTPUT_DIR)/build OUT_DIR=$(OUTPUT_DIR) DRIVER="$(DRIVER_CC)" \
	TOP_DIR=$(chipyard_dir)

$(vitis): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-idirafter ${CONDA_PREFIX}/include -idirafter /usr/include -idirafter $(XILINX_XRT)/include
# -ldl needed for Ubuntu 20.04 systems (is backwards compatible with U18.04 systems)
$(vitis): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' \
	-L${CONDA_PREFIX}/lib -Wl,-rpath-link=/usr/lib/x86_64-linux-gnu -L$(XILINX_XRT)/lib -luuid -lxrt_coreutil -ldl


# Compile Driver
$(vitis): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) vitis PLATFORM=vitis DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(OUTPUT_DIR)/build OUT_DIR=$(OUTPUT_DIR) DRIVER="$(DRIVER_CC)" \
	TOP_DIR=$(chipyard_dir)

#############################
# FPGA Build Initialization #
#############################
ifeq ($(PLATFORM), vitis)
board_dir 	   := $(platforms_dir)/vitis
else
board_dir 	   := $(platforms_dir)/f1/aws-fpga/hdk/cl/developer_designs
endif

fpga_work_dir  := $(board_dir)/cl_$(name_tuple)
fpga_build_dir := $(fpga_work_dir)/build
verif_dir      := $(fpga_work_dir)/verif
repo_state     := $(fpga_work_dir)/design/repo_state
fpga_driver_dir:= $(fpga_work_dir)/driver

# Enumerates the subset of generated files that must be copied over for FPGA compilation
fpga_delivery_files = $(addprefix $(fpga_work_dir)/design/$(BASE_FILE_NAME), \
	.sv .defines.vh \
	.synthesis.xdc .implementation.xdc)

# Files used to run FPGA-level metasimulation
fpga_sim_delivery_files = $(addprefix $(fpga_driver_dir)/$(BASE_FILE_NAME), .runtime.conf) \
	$(fpga_driver_dir)/$(DESIGN)-$(PLATFORM)

$(fpga_work_dir)/stamp: $(shell find $(board_dir)/cl_firesim -name '*')
	mkdir -p $(driver_dir) #Could just set up in the shell project
	cp -rf $(board_dir)/cl_firesim -T $(fpga_work_dir)
	touch $@

$(repo_state): $(simulator_verilog) $(fpga_work_dir)/stamp
	$(firesim_base_dir)/../scripts/repo_state_summary.sh > $(repo_state)

$(fpga_work_dir)/design/$(BASE_FILE_NAME)%: $(simulator_verilog) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/*.ipgen.tcl $(@D) || true
	cp -f $(GENERATED_DIR)/$(@F) $@

$(fpga_driver_dir)/$(BASE_FILE_NAME)%: $(simulator_verilog) $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	cp -f $(GENERATED_DIR)/$(@F) $@

$(fpga_driver_dir)/$(DESIGN)-$(PLATFORM): $($(PLATFORM))
	cp -f $< $@

# Goes as far as setting up the build directory without running the cad job
# Used by the manager before passing a build to a remote machine
replace-rtl: $(fpga_delivery_files) $(fpga_sim_delivery_files)

.PHONY: replace-rtl

$(firesim_base_dir)/scripts/checkpoints/$(target_sim_tuple): $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	ln -sf $(fpga_build_dir)/checkpoints/to_aws $@

# Runs a local fpga-bitstream build. Strongly consider using the manager instead.
fpga: export CL_DIR := $(fpga_work_dir)
fpga: $(fpga_delivery_files) $(base_dir)/scripts/checkpoints/$(target_sim_tuple)
	cd $(fpga_build_dir)/scripts && ./aws_build_dcp_from_cl.sh -notify


#############################
# FPGA-level RTL Simulation #
#############################

# Run XSIM DUT
.PHONY: xsim-dut
xsim-dut: replace-rtl $(fpga_work_dir)/stamp
	cd $(verif_dir)/scripts && $(MAKE) C_TEST=test_firesim

# Compile XSIM Driver #
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

$(xsim): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -D SIMULATION_XSIM -D NO_MAIN
$(xsim): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)
$(xsim): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
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
compile-midas-unittests: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $(unittest_args)

.PHONY:run-midas-unittests
run-midas-unittests: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

.PHONY:run-midas-unittests-debug
run-midas-unittests-debug: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

#########################
# ScalaDoc              #
#########################
scaladoc:
	cd $(base_dir) && $(SBT) "project {file:$(firesim_base_dir)}firesim" "unidoc"

.PHONY: scaladoc

#########################
# Scalafmt              #
#########################
# Checks that all scala main sources under firesim SBT subprojects are formatted.
scalafmtCheckAll:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtCheckAll; \
		firesimLib / scalafmtCheckAll; \
		midas / scalafmtCheckAll ; \
		targetutils / scalafmtCheckAll ;"

# Runs the code reformatter in all firesim SBT subprojects
scalafmtAll:
	cd $(base_dir) && $(SBT) ";project {file:$(firesim_base_dir)}firesim; \
		firesim / scalafmtAll; \
		firesimLib / scalafmtAll; \
		midas / scalafmtAll ; \
		targetutils / scalafmtAll ;"

.PHONY: scalafmtCheckAll scalafmtAll
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

tags: $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .

.PHONY: $(PLATFORM)-driver fpga
.PHONY: mostlyclean clean

.PRECIOUS: $(OUTPUT_DIR)/%.vpd $(OUTPUT_DIR)/%.out $(OUTPUT_DIR)/%.run

# Remove all implicit suffix rules; This improves make performance substantially as it no longer
# attempts to resolve implicit rules on 1000+ scala files.
.SUFFIXES:
