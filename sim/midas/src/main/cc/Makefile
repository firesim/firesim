midas_dir = $(abspath .)
bridge_dir = $(midas_dir)/bridges
core_dir = $(midas_dir)/core
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
# 9) MAIN: platform-specific main to choose from
########################################################################

PLATFORM ?= f1
OUT_DIR ?= $(GEN_DIR)

override CXXFLAGS += -Wall -I$(midas_dir) -I$(GEN_DIR)
override LDFLAGS += -L$(GEN_DIR) -lstdc++ -lpthread -lgmp

DESIGN_V  ?= $(GEN_DIR)/$(GEN_FILE_BASENAME).sv
design_h  := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.h
design_vh := $(GEN_DIR)/$(GEN_FILE_BASENAME).const.vh
driver_h = $(foreach t, $(DRIVER), $(wildcard $(dir $(t))/*.h))

bridge_h := $(wildcard $(bridge_dir)/*.h) $(wildcard $(core_dir)/*.h)
bridge_cc := $(wildcard $(bridge_dir)/*.cc) $(wildcard $(core_dir)/*.cc)
bridge_o := $(patsubst $(midas_dir)/%.cc, $(GEN_DIR)/%.o, $(bridge_cc))
$(bridge_o): $(GEN_DIR)/%.o: $(midas_dir)/%.cc $(bridge_h)
	mkdir -p $(@D)
	$(CXX) $(CXXFLAGS) -c -o $@ $<

platform_files := simif_$(MAIN) main entry
platform_o := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(platform_files)))

$(platform_o): $(GEN_DIR)/%.o: $(midas_dir)/%.cc $(design_h)
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $<

$(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM): $(DRIVER) $(driver_h) $(platform_o) $(bridge_o)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) -o $@ $(DRIVER) $(platform_o) $(bridge_o) $(LDFLAGS)

.PHONY: driver
driver: $(OUT_DIR)/$(DRIVER_NAME)-$(PLATFORM)

# Sources for building MIDAS-level simulators. Must be defined before sources VCS/Verilator Makefrags
override CXXFLAGS += -std=c++20

# Models of FPGA primitives that are used in host-level sim, but not in FPGATop
sim_fpga_resource_models := $(v_dir)/BUFGCE.v

emul_dir   := $(midas_dir)/emul
emul_h     := $(driver_h) $(bridge_h) $(emul_dir)/simif_emul.h $(emul_dir)/mmio.h $(emul_dir)/mm.h $(midas_dir)/entry.h
# This includes c sources and static libraries
emul_cc    := $(DRIVER) $(bridge_cc) $(emul_dir)/simif_emul.cc $(emul_dir)/mmio.cc $(emul_dir)/mm.cc $(emul_dir)/dpi.cc $(midas_dir)/entry.cc
emul_v     := $(design_vh) $(DESIGN_V) $(sim_fpga_resource_models)

TB := emul
CLOCK_PERIOD ?= 1.0

SIM_TIMESCALE ?= 1ps/1ps
SIM_PREPROC_DEFINES = \
	+define+CLOCK_PERIOD=$(CLOCK_PERIOD) \
	+define+RANDOMIZE_MEM_INIT \
	+define+RANDOMIZE_REG_INIT \
	+define+RANDOMIZE_GARBAGE_ASSIGN \
	+define+RANDOMIZE_INVALID_ASSIGN \
	+define+STOP_COND=!$(TB).reset \
	+define+PRINTF_COND=!$(TB).reset \
	+define+RANDOM=32\'b0 \

sim_wrapper_v := $(v_dir)/top.sv
sim_harness := $(midas_dir)/simif_emul_all.cc

include rtlsim/Makefrag-verilator

# tell make not to look for implicit rules for sim sourcefiles (helps --debug=i output)
$(sim_harness): ;

verilator: $(OUT_DIR)/V$(DRIVER_NAME)
verilator-debug: $(OUT_DIR)/V$(DRIVER_NAME)-debug
.PHONY: verilator verilator-debug

include rtlsim/Makefrag-vcs

$(vcs_harness): ;

vcs: $(OUT_DIR)/$(DRIVER_NAME)
vcs-debug: $(OUT_DIR)/$(DRIVER_NAME)-debug
vcs-post-synth: $(OUT_DIR)/$(DRIVER_NAME)-post-synth
vcs-post-synth-debug: $(OUT_DIR)/$(DRIVER_NAME)-post-synth-debug

.PHONY: vcs vcs-debug vcs-post-synth vcs-post-synth-debug

include rtlsim/Makefrag-xcelium

$(xcelium_harness): ;

xcelium: $(xc_sim_name)

.PHONY: xcelium
