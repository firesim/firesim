# See LICENSE for license details.

# FireSim F1 Platform-Specific Makefrag
#
# Provides the following recipes:
# 1) replace-rtl: creates a fresh self-contained FPGA project directory,
#    populated with all generated srcs. This directory can be copied to a remote
#    machine to do the build.
# 2) bitstream: invokes vivado and generates a bitstream for this host platform
#
# Additionally, provides optional recipes for doing complete-host RTL simulation
# (FPGA-level) with a full model of the host FPGA.

# Platform-Specific Makefrags reuse many of the variables and targets defined
# in upstream make fragments including:
GENERATED_DIR ?=
DESIGN ?=
DRIVER_CC ?=
DRIVER_H ?=
PLATFORM ?=
name_tuple ?=

# Targets
VERILOG ?=
HEADER ?=

#############################
# FPGA Build Initialization #
#############################
fpga_dir = $(firesim_base_dir)/../platforms/f1/aws-fpga
board_dir 	   := $(fpga_dir)/hdk/cl/developer_designs
fpga_work_dir  := $(board_dir)/cl_$(name_tuple)
fpga_build_dir      := $(fpga_work_dir)/build
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
	sed -i "s/\(^ *\)fatal;\( *$$\)/\1fatal(0, \"\");\2/g" $@

$(fpga_vh): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

$(fpga_tcl_env): $(VERILOG) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/$(@F) $@

.PHONY: $(ila_work_dir)
$(ila_work_dir): $(verilog) $(fpga_work_dir)/stamp
	cp -f $(GENERATED_DIR)/firesim_ila_insert_* $(fpga_work_dir)/design/ila_files/
	sed -i "s/\$$random/64'b0/g" $(fpga_work_dir)/design/ila_files/*
	sed -i "s/\(^ *\)fatal;\( *$$\)/\1fatal(0, \"\");\2/g" $(fpga_work_dir)/design/ila_files/*

# Goes as far as setting up the build directory without running the cad job
# Used by the manager before passing a build to a remote machine
replace-rtl: $(fpga_v) $(ila_work_dir) $(fpga_vh) $(fpga_tcl_env)

.PHONY: replace-rtl

$(firesim_base_dir)/scripts/checkpoints/$(target_sim_tuple): $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	ln -sf $(fpga_build_dir)/checkpoints/to_aws $@

# Runs a local fpga-bitstream build. Strongly consider using the manager instead.
fpga: export CL_DIR := $(fpga_work_dir)
fpga: $(fpga_v) $(base_dir)/scripts/checkpoints/$(target_sim_tuple)
	cd $(fpga_build_dir)/scripts && ./aws_build_dcp_from_cl.sh -notify


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
	GEN_DIR=$(GENERATED_DIR) OUT_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" \
	TOP_DIR=$(chipyard_dir)

xsim: $(xsim)

.PHONY: xsim-dut xsim run-xsim fpga
