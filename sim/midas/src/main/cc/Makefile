midas_dir = $(abspath .)

bridge_dir = $(midas_dir)/bridges
core_dir = $(midas_dir)/core
emul_dir = $(midas_dir)/emul

v_dir = $(abspath ../verilog)
r_dir = $(abspath ../resources)

########################################################################
# Parameters:
# 1) PLATFORM: FPGA platform board(by default f1)
# 2) DRIVER_NAME: Base name for compiled drivers and ML simulators
# 3) GEN_DIR: Directory for generated source code
# 4) OUT_DIR: Directory for binary files (by default GEN_DIR)
# 5) GEN_FILE_BASENAME: Common prefix for all GG-emitted output files
# 6) DRIVER: software driver written by user
# 7) CLOCK_PERIOD(optional): clock period of tests
# 8) VERILATOR_FLAGS(optional): set of verilator flags to add
########################################################################

PLATFORM ?= f1
OUT_DIR ?= $(GEN_DIR)
CLOCK_PERIOD ?= 1.0

design_v  := $(GEN_DIR)/$(GEN_FILE_BASENAME).sv
design_h  := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.h
design_vh := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.vh
driver_h = $(foreach t, $(DRIVER), $(wildcard $(dir $(t))/*.h))

################################################################################
# FireSim driver library
################################################################################

library_ldflags := \
	$(LDFLAGS) \
	-lpthread \
	-lgmp \
	-Wl,-rpath='$$$$ORIGIN'

library_cxxflags = \
	$(CXXFLAGS) \
	-std=c++17 -Wall \
	-I$(midas_dir) \
	-Wno-unused-variable

core_h := $(wildcard $(core_dir)/*.h)
core_cc := $(wildcard $(core_dir)/*.cc)
core_o := $(patsubst $(core_dir)/%.cc, $(GEN_DIR)/core__%.o, $(core_cc))
$(core_o): $(GEN_DIR)/core__%.o: $(core_dir)/%.cc $(design_h) $(driver_h)
	$(CXX) $(library_cxxflags) -include $(design_h) -c -o $@ $<

bridge_h := $(wildcard $(bridge_dir)/*.h)
bridge_cc := $(wildcard $(bridge_dir)/*.cc)
bridge_o := $(patsubst $(bridge_dir)/%.cc, $(GEN_DIR)/bridge__%.o, $(bridge_cc))
$(bridge_o): $(GEN_DIR)/bridge__%.o: $(bridge_dir)/%.cc $(design_h) $(driver_h)
	$(CXX) $(library_cxxflags) -c -o $@ $<  -include $(design_h)

emul_h := $(wildcard $(emul_dir)/*.h)
emul_cc := $(wildcard $(emul_dir)/*.cc)
emul_o := $(patsubst $(emul_dir)/%.cc, $(GEN_DIR)/emul__%.o, $(emul_cc))
$(emul_o): $(GEN_DIR)/emul__%.o: $(emul_dir)/%.cc $(driver_h)
	$(CXX) $(library_cxxflags) -c -o $@ $<  -include $(design_h)

$(GEN_DIR)/libfiresim.a: $(core_o) $(bridge_o)
	rm -f $@
	$(AR) rcs $@ $^

$(GEN_DIR)/libfiresim-emul.a: $(emul_o)
	rm -f $@
	$(AR) rcs $@ $^

################################################################################
# Compiled design-specific driver
################################################################################

driver_cxxflags = \
	$(CXXFLAGS) \
	-std=c++17 -Wall \
	-I$(midas_dir) \
	-include $(design_h) \
	$(DRIVER_CXX_FLAGS) \
	-Wno-unused-variable

driver_dir = $(abspath $(BASE_DIR)/..)
driver_o = $(patsubst $(driver_dir)/%.cc, $(GEN_DIR)/driver/%.o, $(DRIVER_CC))
$(driver_o): $(GEN_DIR)/driver/%.o: $(driver_dir)/%.cc $(design_h) $(driver_h)
	mkdir -p $(@D)
	$(CXX) $(driver_cxxflags) -c -o $@ $<

$(GEN_DIR)/libdriver.a: $(driver_o)
	rm -f $@
	$(AR) rcs $@ $^

################################################################################
# Platform-specific drivers
################################################################################

platform_cxxflags := \
	$(library_cxxflags)

platform_ldflags := \
	$(library_ldflags) \
	$(PLATFORM_LD_FLAGS) \
	$(TARGET_LD_FLAGS)

platform_file := simif_$(PLATFORM)
platform_cc := $(midas_dir)/$(platform_file).cc
platform_o := $(GEN_DIR)/$(platform_file).o
platform_libs := $(GEN_DIR)/libdriver.a $(GEN_DIR)/libfiresim.a

$(platform_o): $(GEN_DIR)/%.o: $(midas_dir)/%.cc $(design_h) $(core_h) $(emul_h)
	mkdir -p $(@D)
	$(CXX) -o $@ -c $< $(PLATFORM_CXX_FLAGS) $(platform_cxxflags) -include $(design_h)

$(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM): $(platform_o) $(platform_libs)
	mkdir -p $(@D)
	$(CXX) -o $@ -Wl,--start-group $^ -Wl,--end-group  $(platform_ldflags)

.PHONY: $(PLATFORM)
$(PLATFORM): $(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM)

################################################################################
# Verilog harness
################################################################################

# Models of FPGA primitives that are used in host-level sim, but not in FPGATop
sim_fpga_resource_models := $(v_dir)/BUFGCE.v

emul_libs  := $(GEN_DIR)/libdriver.a $(GEN_DIR)/libfiresim-emul.a $(GEN_DIR)/libfiresim.a
emul_h     := $(driver_h) $(core_h) $(bridge_h) $(emul_h)
emul_v     := $(design_vh) $(design_v) $(sim_fpga_resource_models) $(v_dir)/top.sv
emul_top   := emul

################################################################################
# Verilator
################################################################################

VERILATOR ?= verilator

verilator_conf := rtlsim/ml-verilator-conf.vlt
verilator_cc := $(midas_dir)/simif_emul_verilator.cc $(midas_dir)/dpi.cc

verilator_flags := \
	--top-module $(emul_top) \
 	--cc \
 	--exe \
	--timescale 1ns/1ps \
	$(TIMESCALE_OPTS) \
	-Wno-STMTDLY \
	-O3 \
	--output-split 10000 \
	--output-split-cfuncs 100 \
	$(VERILATOR_FLAGS)

verilator_cflags := \
	$(CXXFLAGS) \
	-include $(design_h) \
	-I$(midas_dir)
verilator_ldflags := \
	$(library_ldflags) \
	$(TARGET_LD_FLAGS)

verilator_exe := $(OUT_DIR)/V$(DRIVER_NAME)
verilator_exe_csrc = $(GEN_DIR)/V$(DRIVER_NAME).csrc

.PHONY: verilator
verilator: $(verilator_exe)
$(verilator_exe): $(verilator_exe_csrc)/MAKE $(emul_libs) $(verilator_cc)
	$(MAKE) -C $(verilator_exe_csrc) -f V$(emul_top).mk

$(verilator_exe_csrc)/MAKE: $(emul_v)
	rm -rf $(@D)
	mkdir -p $(@D)

	$(VERILATOR) $(verilator_flags) \
		-Mdir $(verilator_exe_csrc) \
		-o $(verilator_exe) \
		-CFLAGS "-include $(@D)/V$(emul_top).h $(verilator_cflags)" \
		-LDFLAGS "-Wl,--start-group $(emul_libs) -Wl,--end-group $(verilator_ldflags)" \
		$(verilator_conf) \
		$(emul_v) \
		$(verilator_cc)

verilator_exe_debug := $(OUT_DIR)/V$(DRIVER_NAME)-debug
verilator_exe_debug_csrc = $(GEN_DIR)/V$(DRIVER_NAME)-debug.csrc

.PHONY: verilator-debug
verilator-debug: $(verilator_exe_debug) $(emul_libs)
$(verilator_exe_debug): $(verilator_exe_debug_csrc)/MAKE $(emul_libs) $(verilator_cc)
	$(MAKE) -C $(verilator_exe_debug_csrc) -f V$(emul_top).mk

$(verilator_exe_debug_csrc)/MAKE: $(emul_v)
	rm -rf $(@D)
	mkdir -p $(@D)

	$(VERILATOR) $(verilator_flags) --trace \
		-Mdir $(verilator_exe_debug_csrc) \
		-o $(verilator_exe_debug) \
		-CFLAGS "-include $(@D)/V$(emul_top).h $(verilator_cflags)" \
		-LDFLAGS "-Wl,--start-group $(emul_libs) -Wl,--end-group $(verilator_ldflags)" \
		$(verilator_conf) \
		$(emul_v) $(verilator_cc)

################################################################################
# VCS
################################################################################

VCS ?= vcs -full64

vcs_cc := $(midas_dir)/simif_emul_vcs.cc $(midas_dir)/dpi.cc

vcs_cflags := \
	$(CXXFLAGS) \
	-include $(design_h) \
	-I$(midas_dir)

vcs_ldflags := \
	$(library_ldflags) \
	$(TARGET_LD_FLAGS)

vcs_flags := \
	$(VCS_FLAGS) \
	-e vcs_main \
	-quiet \
	-timescale=1ns/1ps \
	+v2k +rad +vcs+initreg+random +vcs+lic+wait \
	-notice -line +lint=all,noVCDE,noONGS,noUI -quiet -debug_acc+pp+dmptf -debug_region+cell+encrypt +no_notifier \
	-cpp $(CXX) \
	-top $(emul_top) \
	+vc+list \
	-sverilog \
	-assert svaext \
	+define+CLOCK_PERIOD=$(CLOCK_PERIOD) \
	+define+RANDOMIZE_GARBAGE_ASSIGN \
	+define+RANDOMIZE_INVALID_ASSIGN \
	+define+STOP_COND=!$(emul_top).reset \
	+define+PRINTF_COND=!$(emul_top).reset


.PHONY: vcs
vcs: $(OUT_DIR)/$(DRIVER_NAME)

vcs_exe := $(OUT_DIR)/$(DRIVER_NAME)
vcs_exe_csrc := $(GEN_DIR)/$(DRIVER_NAME).csrc

$(vcs_exe): $(emul_v) $(vcs_cc) $(emul_libs)
	mkdir -p $(OUT_DIR)
	rm -rf $(vcs_exe_csrc)
	rm -rf $(vcs_exe).daidir
	$(VCS) $(vcs_flags) -o $@ \
		$(emul_v) $(vcs_cc) \
		-CFLAGS "$(vcs_cflags)" \
		-LDFLAGS "-Wl,--start-group $(emul_libs) -Wl,--end-group $(vcs_ldflags)" \
		-Mdir=$(vcs_exe_csrc)

.PHONY: vcs-debug
vcs-debug: $(OUT_DIR)/$(DRIVER_NAME)-debug

vcs_exe_debug := $(OUT_DIR)/$(DRIVER_NAME)-debug
vcs_exe_debug_csrc := $(GEN_DIR)/$(DRIVER_NAME)-debug.csrc

$(vcs_exe_debug): $(emul_v) $(vcs_cc) $(emul_libs)
	mkdir -p $(OUT_DIR)
	rm -rf $(vcs_exe_debug_csrc)
	rm -rf $(vcs_exe_debug).daidir
	$(VCS) $(vcs_flags) -o $@ \
		$(emul_v) $(vcs_cc) \
		-CFLAGS "$(vcs_cflags)" \
		-LDFLAGS "-Wl,--start-group $(emul_libs) -Wl,--end-group $(vcs_ldflags)" \
		+define+DEBUG \
		-Mdir=$(vcs_exe_debug_csrc)
