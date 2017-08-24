midas_dir = $(abspath .)
util_dir = $(midas_dir)/utils
endpoint_dir = $(midas_dir)/endpoints
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

ifneq ($(CXX),cl)
override CXXFLAGS := $(CXXFLAGS) -std=c++11 -Wall -I$(midas_dir)/dramsim2
o = o
else
PATH_CONVERTER ?= cygpath -w
path = $(patsubst %\,%,$(shell $(PATH_CONVERTER) $(1)))
override CXXFLAGS := $(CXXFLAGS) /EHsc /I$(call path,$(midas_dir)/dramsim2)
o = obj
endif

include $(util_dir)/utils.mk

$(OUT_DIR)/dramsim2_ini: $(r_dir)/dramsim2_ini
	ln -sf $< $@

$(OUT_DIR)/$(DESIGN).chain:
	$(if $(wildcard $(GEN_DIR)/$(DESIGN).chain),cp $(GEN_DIR)/$(DESIGN).chain $@,)

ifneq ($(CXX),cl)
override CXXFLAGS += -I$(midas_dir) -I$(util_dir) $(foreach f, $(DRIVER), -I$(dir $(f)))
override LDFLAGS := $(LDFLAGS) -L$(GEN_DIR) -lstdc++ -lpthread 
else
override CXXFLAGS += /I$(call path,$(midas_dir)) /I$(call path,$(util_dir))
override CXXFLAGS += $(foreach f, $(DRIVER), /I$(call path,$(dir $(f))))
endif

design_v = $(GEN_DIR)/$(shim).v
driver_h = $(foreach t, $(DRIVER), $(wildcard $(dir $(t))/*.h))
endpoint_h := $(wildcard $(endpoint_dir)/*.h)
endpoint_cc := $(wildcard $(endpoint_dir)/*.cc)
endpoint_o := $(patsubst $(endpoint_dir)/%.cc, $(GEN_DIR)/%.$(o), $(endpoint_cc))
$(endpoint_o): $(GEN_DIR)/%.$(o): $(endpoint_dir)/%.cc $(GEN_DIR)/$(DESIGN)-const.h $(endpoint_h)
ifneq ($(CXX),cl)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)
else
	$(CXX) $(CXXFLAGS) /c /Fo$(call path,$@) $(call path,$<) /FI$(call path,$(word 2, $^))
endif

platform_files := simif simif_$(PLATFORM) sample/sample
platform_h := $(addprefix $(midas_dir)/, $(addsuffix .h, $(platform_files)))
platform_cc := $(addprefix $(midas_dir)/, $(addsuffix .cc, $(platform_files) sample/simif_sample))
platform_o := $(addprefix $(GEN_DIR)/, $(addsuffix .$(o), $(platform_files) sample/simif_sample))

$(platform_o): $(GEN_DIR)/%.$(o): $(midas_dir)/%.cc $(GEN_DIR)/$(DESIGN)-const.h $(platform_h)
ifneq ($(CXX),cl)
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $< -include $(word 2, $^)
else
	mkdir.exe -p $(dir $@)
	$(CXX) $(CXXFLAGS) /c /Fo$(call path,$@) $(call path,$<) /FI$(call path,$(word 2, $^))
endif

$(OUT_DIR)/$(DESIGN)-$(PLATFORM): $(GEN_DIR)/$(DESIGN)-const.h $(lib) $(DRIVER) $(driver_h) $(platform_o) $(endpoint_o)
ifneq ($(CXX),cl)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -include $< -o $@ $(DRIVER) $(dramsim_o) $(lib_o) $(platform_o) $(endpoint_o)
else
	mkdir.exe -p $(patsubst C:%,%,$(OUT_DIR))
	$(CXX) $(CXXFLAGS) /FI$(call path,$<) -Fe$(call path,$@) \
	$(foreach f, $(DRIVER) $(platform_o) $(endpoint_o) $(lib), $(call path,$(f))) \
	/link $(LDFLAGS)
endif

$(PLATFORM): $(OUT_DIR)/$(DESIGN)-$(PLATFORM) $(OUT_DIR)/$(DESIGN).chain

ifneq ($(CXX),cl) # no emulation, replay with visual studio
emul_files := simif simif_emul emul/mmio_$(PLATFORM) sample/sample
emul_h := $(addprefix $(midas_dir)/, $(addsuffix .h, $(emul_files) emul/mmio))
emul_cc := $(addprefix $(midas_dir)/, $(addsuffix .cc, $(emul_files) sample/simif_sample))
emul_v := $(v_dir)/emul_$(PLATFORM).v

# Compile verilator emulation binary
VERILATOR ?= verilator --cc --exe
override VERILATOR_FLAGS := --assert -Wno-STMTDLY -O3 -CFLAGS "$(CXXFLAGS)" -LDFLAGS "$(LDFLAGS) -lmidas" $(VERILATOR_FLAGS)

$(OUT_DIR)/V$(DESIGN): $(GEN_DIR)/$(DESIGN)-const.h $(design_v) $(lib) $(DRIVER) $(driver_h) $(emul_cc) $(emul_h) $(endpoint_cc) $(endpoint_h)
	mkdir -p $(OUT_DIR)
	rm -rf $(GEN_DIR)/V$(DESIGN).csrc
	$(VERILATOR) $(VERILATOR_FLAGS) --top-module $(shim) -Mdir $(GEN_DIR)/V$(DESIGN).csrc \
	-CFLAGS "-include $< -include $(GEN_DIR)/V$(DESIGN).csrc/V$(shim).h" \
	-o $@ $(design_v) $(DRIVER) $(emul_cc) $(endpoint_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN).csrc -f V$(shim).mk

$(OUT_DIR)/V$(DESIGN)-debug: $(GEN_DIR)/$(DESIGN)-const.h $(design_v) $(lib) $(DRIVER) $(driver_h) $(emul_cc) $(emul_h) $(endpoint_cc) $(endpoint_h)
	mkdir -p $(OUT_DIR)
	rm -rf $(GEN_DIR)/V$(DESIGN)-debug.csrc
	$(VERILATOR) $(VERILATOR_FLAGS) --trace --top-module $(shim) -Mdir $(GEN_DIR)/V$(DESIGN)-debug.csrc \
	-CFLAGS "-include $< -include $(GEN_DIR)/V$(DESIGN)-debug.csrc/V$(shim).h" \
	-o $@ $(design_v) $(DRIVER) $(emul_cc) $(endpoint_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN)-debug.csrc -f V$(shim).mk

verilator: $(OUT_DIR)/V$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
verilator-debug: $(OUT_DIR)/V$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Compile VCS emulation binary
VCS ?= vcs -full64
override VCS_FLAGS := -quiet -timescale=1ns/1ps +v2k +rad +vcs+initreg+random +vcs+lic+wait -debug_pp +no_notifier \
	-e vcs_main -cpp $(CXX) -CFLAGS "$(CXXFLAGS) -DVCS -I$(VCS_HOME)/include" -LDFLAGS "$(LDFLAGS) -lmidas" \
	+define+CLOCK_PERIOD=$(CLOCK_PERIOD) \
	+define+RANDOMIZE_MEM_INIT \
	+define+RANDOMIZE_REG_INIT \
	+define+RANDOMIZE_GARBAGE_ASSIGN \
	+define+RANDOMIZE_INVALID_ASSIGN \
	$(VCS_FLAGS)

$(OUT_DIR)/$(DESIGN): $(GEN_DIR)/$(DESIGN)-const.h $(design_v) $(emul_v) $(lib) $(DRIVER) $(driver_h) $(emul_cc) $(emul_h) $(endpoint_cc) $(endpoint_h)
	mkdir -p $(OUT_DIR)
	rm -rf $(GEN_DIR)/$(DESIGN).csrc
	rm -rf $(OUT_DIR)/$(DESIGN).daidir
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN).csrc +vc+list \
	+define+STOP_COND=!emul.reset +define+PRINTF_COND=!emul.reset \
	-CFLAGS "-include $<" \
	-o $@ $(GEN_DIR)/$(DESIGN)-const.vh $(design_v) $(emul_v) $(lib) $(DRIVER) $(emul_cc) $(endpoint_cc)

$(OUT_DIR)/$(DESIGN)-debug: $(GEN_DIR)/$(DESIGN)-const.h $(design_v) $(emul_v) $(lib) $(DRIVER) $(driver_h) $(emul_cc) $(emul_h) $(endpoint_cc) $(endpoint_h)
	mkdir -p $(OUT_DIR)
	rm -rf $(GEN_DIR)/$(DESIGN)-debug.csrc
	rm -rf $(OUT_DIR)/$(DESIGN)-debug.daidir
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-debug.csrc +vc+list \
	+define+STOP_COND=!emul.reset +define+PRINTF_COND=!emul.reset +define+DEBUG \
	-CFLAGS "-include $<" \
	-o $@ $(GEN_DIR)/$(DESIGN)-const.vh $(design_v) $(emul_v) $(lib) $(DRIVER) $(emul_cc) $(endpoint_cc)

vcs: $(OUT_DIR)/$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
vcs-debug: $(OUT_DIR)/$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

include $(replay_dir)/replay.mk

endif

.PHONY: $(PLATFORM) verilator verilator-debug vcs vcs-debug
