midas_dir = $(abspath .)
util_dir = $(midas_dir)/utils
bridge_dir = $(midas_dir)/bridges
replay_dir = $(midas_dir)/replay
v_dir = $(abspath ../verilog)
r_dir = $(abspath ../resources)

########################################################################
# Parameters:
# 1) PLATFORM: FPGA platform board(by default zynq)
# 2) DESIGN: Target design of midas
# 3) GEN_DIR: Directory for generated source code
# 4) OUT_DIR: Directory for binary files (by default GEN_DIR)
# 5) DRIVER: software driver written by user (not necessary for replay)
# 6) CLOCK_PERIOD(optional): clock period of tests
########################################################################
ifeq ($(strip $(DESIGN)),)
$(error Define DESIGN, the target design)
endif
ifeq ($(strip $(GEN_DIR)),)
$(error Define GEN_DIR, where all midas generated code reside)
endif
ifeq ($(filter $(MAKECMDGOALS),vcs-replay $(REPLAY_BINARY)),)
ifeq ($(strip $(DRIVER)),)
$(error Define DRIVER, the source code of the simulation driver)
endif
endif

PLATFORM ?= zynq
OUT_DIR ?= $(GEN_DIR)
CLOCK_PERIOD ?= 1.0

$(info platform: $(PLATFORM))
$(info target design: $(DESIGN))
$(info generated source directory: $(GEN_DIR))
$(info output directory: $(OUT_DIR))
$(info driver source files: $(DRIVER))
$(info clock period: $(CLOCK_PERIOD))

shim := FPGATop

override CXXFLAGS := $(CXXFLAGS) -std=c++11 -Wall -I$(midas_dir)/dramsim2

include $(util_dir)/utils.mk

$(OUT_DIR)/dramsim2_ini: $(r_dir)/dramsim2_ini
	ln -sf $< $@

$(OUT_DIR)/$(DESIGN).chain:
	$(if $(wildcard $(GEN_DIR)/$(DESIGN).chain),cp $(GEN_DIR)/$(DESIGN).chain $@,)

override CXXFLAGS += -I$(midas_dir) -I$(util_dir)
# The trailing whitespace is important for some reason...
override LDFLAGS := $(LDFLAGS) -L$(GEN_DIR) -lstdc++ -lpthread -lgmp -lmidas 

design_v  := $(GEN_DIR)/$(shim).v
design_h  := $(GEN_DIR)/$(DESIGN)-const.h
design_vh := $(GEN_DIR)/$(DESIGN)-const.vh
driver_h = $(foreach t, $(DRIVER), $(wildcard $(dir $(t))/*.h))
bridge_h := $(wildcard $(bridge_dir)/*.h)
bridge_cc := $(wildcard $(bridge_dir)/*.cc)
bridge_o := $(patsubst $(bridge_dir)/%.cc, $(GEN_DIR)/%.o, $(bridge_cc))
$(bridge_o): $(GEN_DIR)/%.o: $(bridge_dir)/%.cc $(design_h) $(bridge_h)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)

platform_files := simif simif_$(PLATFORM) sample/sample
platform_h := $(addprefix $(midas_dir)/, $(addsuffix .h, $(platform_files)))
platform_cc := $(addprefix $(midas_dir)/, $(addsuffix .cc, $(platform_files) sample/simif_sample))
platform_o := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(platform_files) sample/simif_sample))

$(platform_o): $(GEN_DIR)/%.o: $(midas_dir)/%.cc $(design_h) $(platform_h)
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)

$(OUT_DIR)/$(DESIGN)-$(PLATFORM): $(design_h) $(lib) $(DRIVER) $(driver_h) $(platform_o) $(bridge_o)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) -include $< \
	-o $@ $(DRIVER) $(dramsim_o) $(lib_o) $(platform_o) $(bridge_o) $(LDFLAGS)

$(PLATFORM): $(OUT_DIR)/$(DESIGN)-$(PLATFORM) $(OUT_DIR)/$(DESIGN).chain

# Sources for building MIDAS-level simulators. Must be defined before sources VCS/Verilator Makefrags
override CFLAGS += -include $(design_h)

# Models of FPGA primitives that are used in host-level sim, but not in FPGATop
sim_fpga_resource_models := $(v_dir)/BUFGCE.v

emul_files := simif simif_emul emul/mmio_$(PLATFORM) sample/sample
emul_h     := $(driver_h) $(bridge_h)  $( $(addprefix $(midas_dir)/, $(addsuffix .h, $(emul_files) emul/mmio)))
# This includes c sources and static libraries
emul_cc    := $(DRIVER) $(bridge_cc) $(addprefix $(midas_dir)/, $(addsuffix .cc, $(emul_files) sample/simif_sample)) $(lib)
emul_v     := $(design_vh) $(design_v) $(sim_fpga_resource_models)

# The lop level module must be called out for verilator
ifeq ($(PLATFORM),zynq)
top_module = ZynqShim
endif
ifeq ($(PLATFORM),f1)
top_module = F1Shim
endif

verilator_conf := rtlsim/ml-verilator-conf.vlt
include rtlsim/Makefrag-verilator

verilator: $(OUT_DIR)/V$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
verilator-debug: $(OUT_DIR)/V$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Add an extra wrapper source for VCS simulators
vcs_wrapper_v := $(v_dir)/emul_$(PLATFORM).v
TB := emul
VCS_FLAGS := -e vcs_main
include rtlsim/Makefrag-vcs

vcs: $(OUT_DIR)/$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
vcs-debug: $(OUT_DIR)/$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

include $(replay_dir)/replay.mk

.PHONY: $(PLATFORM) verilator verilator-debug vcs vcs-debug
