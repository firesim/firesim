midas_dir = $(abspath .)
bridge_dir = $(midas_dir)/bridges
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

override CXXFLAGS += -Wall -I$(midas_dir)
override LDFLAGS += -L$(GEN_DIR) -lstdc++ -lpthread -lgmp

design_v  := $(GEN_DIR)/$(GEN_FILE_BASENAME).sv
design_h  := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.h
design_vh := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.vh
driver_h = $(foreach t, $(DRIVER), $(wildcard $(dir $(t))/*.h))
bridge_h := $(wildcard $(bridge_dir)/*.h)
bridge_cc := $(wildcard $(bridge_dir)/*.cc)
bridge_o := $(patsubst $(bridge_dir)/%.cc, $(GEN_DIR)/%.o, $(bridge_cc))
$(bridge_o): $(GEN_DIR)/%.o: $(bridge_dir)/%.cc $(design_h) $(bridge_h)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)

platform_files := simif simif_$(PLATFORM)
platform_h := $(addprefix $(midas_dir)/, $(addsuffix .h, $(platform_files)))
platform_cc := $(addprefix $(midas_dir)/, $(addsuffix .cc, $(platform_files)))
platform_o := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(platform_files)))

$(platform_o): $(GEN_DIR)/%.o: $(midas_dir)/%.cc $(design_h) $(platform_h)
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)

$(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM): $(design_h) $(DRIVER) $(driver_h) $(platform_o) $(bridge_o)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) -include $< \
	-o $@ $(DRIVER) $(platform_o) $(bridge_o) $(LDFLAGS)

$(PLATFORM): $(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM)

# Sources for building MIDAS-level simulators. Must be defined before sources VCS/Verilator Makefrags
override CXXFLAGS += -std=c++17 -include $(design_h)

# Models of FPGA primitives that are used in host-level sim, but not in FPGATop
sim_fpga_resource_models := $(v_dir)/BUFGCE.v

emul_files := simif simif_emul emul/mmio emul/mm
emul_h     := $(driver_h) $(bridge_h) $(addprefix $(midas_dir)/, $(addsuffix .h, $(emul_files)))
# This includes c sources and static libraries
emul_cc    := $(DRIVER) $(bridge_cc) $(addprefix $(midas_dir)/, $(addsuffix .cc, $(emul_files)))
emul_v     := $(design_vh) $(design_v) $(sim_fpga_resource_models)

verilator_conf := rtlsim/ml-verilator-conf.vlt
verilator_wrapper_v := $(v_dir)/top.sv
verilator_harness := emul/dpi.cc simif_emul_verilator.cc
top_module := emul
include rtlsim/Makefrag-verilator

verilator: $(OUT_DIR)/V$(DRIVER_NAME)
verilator-debug: $(OUT_DIR)/V$(DRIVER_NAME)-debug

# Add an extra wrapper source for VCS simulators
vcs_wrapper_v := $(v_dir)/top.sv
vcs_harness := emul/dpi.cc simif_emul_vcs.cc
TB := emul
VCS_FLAGS := -e vcs_main
include rtlsim/Makefrag-vcs

vcs: $(OUT_DIR)/$(DRIVER_NAME)
vcs-debug: $(OUT_DIR)/$(DRIVER_NAME)-debug

.PHONY: $(PLATFORM) verilator verilator-debug vcs vcs-debug
