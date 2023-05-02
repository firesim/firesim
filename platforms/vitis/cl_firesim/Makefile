# Makefile to build Vitis based FireSim
#
# Assumptions:
#   Host must be x86
#   You have the Vitis 2022.1 + XRT tools installed

buildroot = $(abspath .)
PROJECT_NAME := firesim

.PHONY: help
help::
	@echo "make sim or bitstream or clean"

ifndef XILINX_VITIS
	$(error XILINX_VITIS variable is not set, please set correctly and rerun)
endif

# taken from https://stackoverflow.com/questions/26145267/how-do-i-force-a-target-to-be-rebuilt-if-a-variable-is-set
# variable argument to DEPENDABLE_VAR can be used as a prerequisite
define DEPENDABLE_VAR

.PHONY: phony
$1: phony
	@if [[ `cat $1 2>&1` != '$($1)' ]]; then \
		echo -n $($1) > $1 ; \
	fi

endef

# Builds through vitis use the same tool (v++) for building xclbins destined
# for FPGA deployment and various simulation. This is controlled by the --target, -t flag
#
# To clearly disambiguate between simulation (--target hw_emu) and bitstream builds (--target hw),
# these flows are run under $(SIM_DIR) and $(BITSTREAM_DIR) respectively.
BITSTREAM_DIR  := $(buildroot)/bitstream
SIM_DIR        := $(buildroot)/simulation

# Many of the arguments passed to v++ are the same across both flows. Use Make's target-specific
# variable assignment to provide those that differ.
bitstream: TARGET = hw
bitstream: VPP_TARGET_SPECIFIC_ARGS = --target $(TARGET) --config $(buildroot)/build-bitstream.cfg
sim:       TARGET = hw_emu
sim:       VPP_TARGET_SPECIFIC_ARGS = --target $(TARGET) --config $(buildroot)/simulation.cfg
run-sim-rc:   TARGET = hw_emu
run-sim-rc:   VPP_TARGET_SPECIFIC_ARGS = --target $(TARGET) --config $(buildroot)/simulation.cfg
run-sim-printf:   TARGET = hw_emu
run-sim-printf:   VPP_TARGET_SPECIFIC_ARGS = --target $(TARGET) --config $(buildroot)/simulation.cfg

# current support on firesim1
DEVICE ?= xilinx_u250_gen3x16_xdma_3_1_202020_1

# Provide's the desired simulator frequency. This is generated from the clock
# provided to the kernel using an internal MMCM.
FREQUENCY ?= 30

# declare FREQUENCY to be dependable
$(eval $(call DEPENDABLE_VAR,FREQUENCY))

# Selects a vitis config, by looking in build-strategies, for an equivalently
# named cfg file.
STRATEGY ?= TIMING

# If set, this will very first config file vitis sees.
CONFIG_OVERLAY ?=

#   device2xsa - create a filesystem friendly name from device name
#   $(1) - full name of device
device2xsa = $(strip $(patsubst %.xpfm, % , $(shell basename $(DEVICE))))


XSA := $(call device2xsa, $(DEVICE))
MSG_RULES      := $(buildroot)/msg.mrf
DRIVER_DIR     := $(buildroot)/driver
STRATEGIES_DIR := $(buildroot)/build-strategies

# Keep the directory structure under $(SIM_DIR) and $(BITSTREAM_DIR) largely the same
BUILD_SUBDIR   := build_dir.$(XSA)
TEMP_SUBDIR    := _x.$(XSA)
LOG_SUBDIR     := $(XSA).logs
REPORT_SUBDIR  := $(XSA).reports

TEMP_DIR       := $(BITSTREAM_DIR)/$(TEMP_SUBDIR)
BUILD_DIR      := $(BITSTREAM_DIR)/$(BUILD_SUBDIR)
LOG_DIR        := $(BITSTREAM_DIR)/$(LOG_SUBDIR)
REPORT_DIR     := $(BITSTREAM_DIR)/$(REPORT_SUBDIR)

SIM_TEMP_DIR   := $(SIM_DIR)/$(TEMP_SUBDIR)
SIM_BUILD_DIR  := $(SIM_DIR)/$(BUILD_SUBDIR)
SIM_LOG_DIR    := $(SIM_DIR)/$(LOG_SUBDIR)
SIM_REPORT_DIR := $(SIM_DIR)/$(REPORT_SUBDIR)
SIM_RUN_DIR    := $(SIM_DIR)/$(XSA).run

BINARY_CONTAINER          = $(BUILD_DIR)/$(PROJECT_NAME).xclbin
BINARY_CONTAINER_OBJ      = $(TEMP_DIR)/$(PROJECT_NAME).xo
BINARY_CONTAINER_LINK_OBJ = $(TEMP_DIR)/$(PROJECT_NAME).link.xclbin

SIM_BINARY_CONTAINER          = $(SIM_BUILD_DIR)/$(PROJECT_NAME).xclbin
SIM_BINARY_CONTAINER_OBJ      = $(SIM_TEMP_DIR)/$(PROJECT_NAME).xo
SIM_BINARY_CONTAINER_LINK_OBJ = $(SIM_TEMP_DIR)/$(PROJECT_NAME).link.xclbin


.PHONY: sim
sim: $(SIM_BINARY_CONTAINER)

.PHONY: bitstream
bitstream: $(BINARY_CONTAINER)

PACKAGE_OUT = ./package.$(TARGET)

# Building kernel (xclbin)
VPP := $(XILINX_VITIS)/bin/v++
# Arguments passed to v++. Note, this will run out of the $(SIM_DIR) or
# $(BITSTREAM_DIR) so relative paths can use $(SUB_DIR)
VPP_OTHER_FLAGS += --save-temps -R2 -g
VPP_MSG_FLAGS = --message-rules $(MSG_RULES) --log_dir $(LOG_SUBDIR) --report_dir $(REPORT_SUBDIR)
VPP_COMMON_ARGS = \
	--platform $(DEVICE) \
	--temp_dir $(TEMP_SUBDIR) \
	$(VPP_OTHER_FLAGS) \
	$(VPP_MSG_FLAGS)


ifneq ($(CONFIG_OVERLAY),)
ifneq ($(shell test -f $(CONFIG_OVERLAY); echo $$?),0)
$(error Vitis config overlay at $(CONFIG_OVERLAY) does not exist)
endif
vpp_config_overlays := $(abspath $(CONFIG_OVERLAY))
endif

PROFILE := no
ifeq ($(PROFILE), yes)
	VPP_LDFLAGS += --profile.data all:all:all
endif
DEBUG := no
ifeq ($(DEBUG), yes)
	VPP_LDFLAGS += --debug.list_ports
endif

VIVADO := $(XILINX_VIVADO)/bin/vivado

# I'm strugging to get Vitis to accept verilog macros passed in on the command
# line Instead, tack on an a `include to the generated verilog file to pull in
# the right macro definitions.
sv_generated = design/FireSim-generated.sv
sv_processed = design/FireSim-generated.post-processed.sv
sv_defines   = design/defines.vh

$(sv_processed): $(sv_generated)
	echo "\`include \"$(notdir $(sv_defines))\"" > $@
	cat $< >> $@

ipgen_scripts = $(shell find design/ -iname "*.ipgen.tcl")

%.xo: scripts/package_kernel.tcl scripts/gen_xo.tcl $(sv_processed) $(sv_defines) $(ipgen_scripts) FREQUENCY
	mkdir -p $(@D)
	# Move into the simulation or bitstream-build directory
	cd $(@D)/..; $(VIVADO) -mode batch -source $(buildroot)/scripts/gen_xo.tcl \
		-tclargs $@ $(PROJECT_NAME) $(TARGET) $(DEVICE) $(XSA) $(FREQUENCY)

build_strategy_path = $(STRATEGIES_DIR)/strategy_$(STRATEGY).cfg

ifneq ($(shell test -f $(build_strategy_path); echo $$?),0)
$(error Build strategy config at $(build_strategy_path) does not exist)
endif
vpp_config_overlays += $(build_strategy_path)
vpp_config_overlay_args = $(addprefix --config ,$(vpp_config_overlays))

# link and package the xclbin
%/$(PROJECT_NAME).link.xclbin: %/$(PROJECT_NAME).xo $(vpp_config_overlays)
	mkdir -p $(@D)
	# Move into temp directory to avoid polluting top-level workdir
	cd $(@D)/..; $(VPP) $(vpp_config_overlay_args) $(VPP_TARGET_SPECIFIC_ARGS) $(VPP_COMMON_ARGS) \
	--link $(VPP_LDFLAGS) -o $@ $<


%/$(BUILD_SUBDIR)/$(PROJECT_NAME).xclbin: %/$(TEMP_SUBDIR)/$(PROJECT_NAME).link.xclbin $(vpp_config_overlays)
	mkdir -p $(@D)
	# Move into temp directory to avoid polluting top-level workdir
	cd $(@D)/..; $(VPP) $(vpp_config_overlay_args) $(VPP_TARGET_SPECIFIC_ARGS) $(VPP_COMMON_ARGS) --package --package.out_dir $(PACKAGE_OUT) -o $@ $<

# FPGA-level simulation support
driver_bin = PrintfModule-vitis
driver_bin2 = FireSim-vitis

# Hardware Emulation Configuration -- specifies the platform to model during simulation
# See https://www.xilinx.com/html_docs/xilinx2021_1/vitis_doc/emconfigutil.html
emconfig = $(SIM_RUN_DIR)/emconfig.json
$(SIM_RUN_DIR)/emconfig.json:
	emconfigutil --platform $(DEVICE) --od $(SIM_RUN_DIR)

# Populate the run directory. Vitis wants certain files to reside in the same
# directory as the application binary, keep things separated by building up a
# fresh location that can be cleanly removed.
$(SIM_RUN_DIR)/%: $(DRIVER_DIR)/%
	mkdir -p $(@D)
	cp -f $< $@

# Vitis Hardware Emulation provides three debugging modes
# batch = waves dumped at the end of simulation
# gui   = interactive session, with a GUI launched at time 0
# none  = no waves
DEBUG_MODE ?= batch

.PHONY: run-sim-printf
run-sim-printf: $(SIM_BINARY_CONTAINER) $(addprefix $(SIM_RUN_DIR)/, $(driver_bin)) $(emconfig)
	mkdir -p $(SIM_RUN_DIR)
	@echo "[Emulation]"                                            >  $(SIM_RUN_DIR)/xrt.ini
	@echo "user_pre_sim_script=../../scripts/xsim-setup-waves.tcl" >> $(SIM_RUN_DIR)/xrt.ini
	@echo "debug_mode=$(DEBUG_MODE)"                               >> $(SIM_RUN_DIR)/xrt.ini # wave dump at end
	cd $(SIM_RUN_DIR) && XCL_EMULATION_MODE=hw_emu ./$(driver_bin) \
    +permissive \
    +slotid=0 \
    +binary_file=$(SIM_BINARY_CONTAINER) \
    +permissive-off \
    </dev/null

.PHONY: run-sim-rc
run-sim-rc: $(SIM_BINARY_CONTAINER) $(addprefix $(SIM_RUN_DIR)/, $(driver_bin2)) $(emconfig)
	mkdir -p $(SIM_RUN_DIR)
	@echo "[Emulation]"                                            >  $(SIM_RUN_DIR)/xrt.ini
	@echo "user_pre_sim_script=../../scripts/xsim-setup-waves.tcl" >> $(SIM_RUN_DIR)/xrt.ini
	@echo "debug_mode=$(DEBUG_MODE)"                               >> $(SIM_RUN_DIR)/xrt.ini # wave dump at end
	cd $(SIM_RUN_DIR) && XCL_EMULATION_MODE=hw_emu ./$(driver_bin2) \
    +permissive \
    +fesvr-step-size=128 \
    +slotid=0 \
    +binary_file=$(SIM_BINARY_CONTAINER) \
    +permissive-off \
    +prog0=$(buildroot)/../cl_firesim/bare0-hello \
    </dev/null

.PHONY: clean
clean:
	rm -rf $(SIM_DIR) $(BITSTREAM_DIR)
