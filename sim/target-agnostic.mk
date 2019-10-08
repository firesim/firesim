# See LICENSE for license details.

# FireSim MAKEFRAG interface - Compulsory variables follow
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
midas_sbt_project := {file:$(firesim_base_dir)}midas

$(VERILOG) $(HEADER): $(FIRRTL_FILE) $(ANNO_FILE)
	cd $(base_dir) && $(SBT) "project $(midas_sbt_project)" "runMain midas.stage.GoldenGateMain \
		-o $(VERILOG) -i $(FIRRTL_FILE) -td $(GENERATED_DIR) \
		-ggaf $(ANNO_FILE) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		-E verilog"
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
conf: $(ANNO_FILE)
	mkdir -p $(GENERATED_DIR)
	cd $(base_dir) && \
	$(SBT) "project $(midas_sbt_project)" "runMain midas.stage.RuntimeConfigGeneratorMain \
		-td $(GENERATED_DIR) \
		-ggaf $(ANNO_FILE) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		-ggrc $(CONF_NAME)"

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_CXXOPTS ?= -O0

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VERILATOR_CXXOPTS) -D RTLSIM
$(verilator) $(verilator_debug): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)

$(verilator): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) verilator PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)"

$(verilator_debug): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) verilator-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)"

verilator: $(verilator)
verilator-debug: $(verilator_debug)

##############################
# VCS MIDAS-Level Simulators #
##############################

VCS_CXXOPTS ?= -O2

vcs = $(GENERATED_DIR)/$(DESIGN)
vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug

$(vcs) $(vcs_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VCS_CXXOPTS) -I$(VCS_HOME)/include -D RTLSIM
$(vcs) $(vcs_debug): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)

$(vcs): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) vcs PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)"

$(vcs_debug): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) vcs-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)"

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

$(f1): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) -I$(fpga_dir)/sdk/userspace/include
# Statically link libfesvr to make it easier to distribute drivers to f1 instances
$(f1): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -lfpga_mgmt

# Compile Driver
$(f1): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(runtime_conf)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(HEADER) $(OUTPUT_DIR)/build/
	cp -f $(GENERATED_DIR)/$(CONF_NAME) $(OUTPUT_DIR)/runtime.conf
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(OUTPUT_DIR)/build OUT_DIR=$(OUTPUT_DIR) DRIVER="$(DRIVER_CC)"

#############################
# FPGA Build Initialization #
#############################
board_dir 	   := $(fpga_dir)/hdk/cl/developer_designs
fpga_work_dir  := $(board_dir)/cl_$(name_tuple)
build_dir      := $(fpga_work_dir)/build
verif_dir      := $(fpga_work_dir)/verif
fpga_v         := $(fpga_work_dir)/design/cl_firesim_generated.sv
ila_work_dir   := $(fpga_work_dir)/design/ila_files/
fpga_vh        := $(fpga_work_dir)/design/cl_firesim_generated_defines.vh
fpga_tcl_env   := $(fpga_work_dir)/design/cl_firesim_generated_env.tcl
repo_state     := $(fpga_work_dir)/design/repo_state

$(fpga_work_dir)/stamp: $(shell find $(board_dir)/cl_firesim -name '*')
	mkdir -p $(@D)
	cp -rf $(board_dir)/cl_firesim -T $(fpga_work_dir)
	touch $@

$(fpga_v): $(VERILOG) $(fpga_work_dir)/stamp
	$(firesim_base_dir)/../scripts/repo_state_summary.sh > $(repo_state)
	cp -f $< $@
	sed -i "s/\$$random/64'b0/g" $@
	sed -i 's/fatal/fatal(0, "")/g' $@

$(fpga_vh): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

$(fpga_tcl_env): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

.PHONY: $(ila_work_dir)
$(ila_work_dir): $(verilog) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/firesim_ila_insert_* $(fpga_work_dir)/design/ila_files/
	sed -i "s/\$$random/64'b0/g" $(fpga_work_dir)/design/ila_files/*
	sed -i 's/fatal/fatal(0, "")/g' $(fpga_work_dir)/design/ila_files/*

# Goes as far as setting up the build directory without running the cad job
# Used by the manager before passing a build to a remote machine
replace-rtl: $(fpga_v) $(ila_work_dir) $(fpga_vh) $(fpga_tcl_env)

.PHONY: replace-rtl

$(firesim_base_dir)/scripts/checkpoints/$(target_sim_tuple): $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	ln -sf $(build_dir)/checkpoints/to_aws $@

# Runs a local fpga-bitstream build. Strongly consider using the manager instead.
fpga: export CL_DIR := $(fpga_work_dir)
fpga: $(fpga_v) $(base_dir)/scripts/checkpoints/$(target_sim_tuple)
	cd $(build_dir)/scripts && ./aws_build_dcp_from_cl.sh -notify


#############################
# FPGA-level RTL Simulation #
#############################

# Run XSIM DUT
xsim-dut: replace-rtl $(fpga_work_dir)/stamp
	cd $(verif_dir)/scripts && $(MAKE) C_TEST=test_firesim

# Compile XSIM Driver #
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

$(xsim): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -D SIMULATION_XSIM -D NO_MAIN
$(xsim): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)
$(xsim): $(HEADER) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(GENERATED_DIR) OUT_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)"

xsim: $(xsim)

#########################
# MIDAS Unit Tests      #
#########################
UNITTEST_CONFIG ?= AllUnitTests

ifdef FIRESIM_STANDALONE
	firesimLib_sbt_project := firesim
else
	firesimLib_sbt_project := {file:${firesim_base_dir}/}firesimLib
endif

rocketchip_dir := $(chipyard_dir)/generators/rocket-chip
unittest_generated_dir := $(base_dir)/generated-src/unittests/$(UNITTEST_CONFIG)
unittest_args = \
		BASE_DIR=$(base_dir) \
		EMUL=$(EMUL) \
		ROCKETCHIP_DIR=$(rocketchip_dir) \
		GEN_DIR=$(unittest_generated_dir) \
		SBT="$(SBT) \"project $(firesimLib_sbt_project)\" " \
		CONFIG=$(UNITTEST_CONFIG)

run-midas-unittests: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

run-midas-unittests-debug: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)


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

.PHONY: sbt test
.PHONY: default verilog compile
.PHONY: verilator verilator-debug
.PHONY: vcs vcs-debug
.PHONY: run
.PHONY: xsim-dut xsim run-xsim
.PHONY: $(PLATFORM)-driver fpga
.PHONY: mostlyclean clean

.PRECIOUS: $(OUTPUT_DIR)/%.vpd $(OUTPUT_DIR)/%.out $(OUTPUT_DIR)/%.run
