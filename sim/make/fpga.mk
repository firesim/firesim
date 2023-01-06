# See LICENSE for license details.

#############################
# FPGA Build Initialization #
#############################

platforms_dir := $(abspath $(firesim_base_dir)/../platforms)

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
.PHONY: fpga
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
# Cleaning Recipes      #
#########################

.PHONY: cleanfpga
cleanfpga:
	rm -rf $(fpga_work_dir)
