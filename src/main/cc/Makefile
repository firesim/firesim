base_dir = $(abspath .)
util_dir = $(base_dir)/utils
v_dir = $(abspath ../verilog)
r_dir = $(abspath ../resources)

CXXFLAGS := $(CXXFLAGS) -std=c++11 -Wall -I$(base_dir)/dramsim2

include $(util_dir)/Makefrag

########################################################################
# Parameters: 
# 1) DESIGN: Target design of strober
# 2) GEN_DIR: Directory for generated source code
# 3) OUT_DIR: Directory for binary files (by default GEN_DIR)
# 4) TESTBENCH: C++ testbench written by user (not necessary for replay)
# 5) CLOCK_PERIOD(optional): clock period of tests
########################################################################
OUT_DIR ?= $(GEN_DIR)
CLOCK_PERIOD ?= 0.5

CXXFLAGS := $(CXXFLAGS) -I$(base_dir) -I$(util_dir) $(if $(TESTBENCH),-I$(dir $(TESTBENCH)))
LDFLAGS := $(LDFLAGS) -L$(util_dir) -lstdc++

zynq_files := sample simif simif_zynq
zynq_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(zynq_files)))
zynq_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(zynq_files)))

$(OUT_DIR)/$(DESIGN)-zynq: $(TESTBENCH) $(util_dir)/biguint.cc $(util_dir)/biguint.h $(zynq_cc) $(zynq_h)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) -include $(GEN_DIR)/$(DESIGN)-const.h -o $@ $< $(word 2, $^) $(zynq_cc)

zynq: $(OUT_DIR)/$(DESIGN)-zynq


$(OUT_DIR)/dramsim2_ini: $(r_dir)/dramsim2_ini
	ln -sf $< $@

$(OUT_DIR)/$(DESIGN).chain: $(GEN_DIR)/$(DESIGN).chain
	cp -n $< $@

emul_files := sample simif simif_emul
emul_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(emul_files)))
emul_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(emul_files)))

# Compile verilator emulation binary
VERILATOR := verilator --cc --exe
VERILATOR_FLAGS := --assert -Wno-STMTDLY -O3 -CFLAGS "$(CXXFLAGS)"

$(OUT_DIR)/V$(DESIGN): $(GEN_DIR)/ZynqShim.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) --top-module ZynqShim -Mdir $(GEN_DIR)/V$(DESIGN).csrc \
	-CFLAGS "-include $(GEN_DIR)/$(DESIGN)-const.h" \
	-CFLAGS "-include $(GEN_DIR)/V$(DESIGN).csrc/VZynqShim.h" \
	-LDFLAGS "$(LDFLAGS) -lemul -lpthread" \
	-o $@ $< $(TESTBENCH) $(emul_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN).csrc -f VZynqShim.mk

$(OUT_DIR)/V$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) --trace --top-module ZynqShim -Mdir $(GEN_DIR)/V$(DESIGN)-debug.csrc \
	-CFLAGS "-include $(GEN_DIR)/$(DESIGN)-const.h" \
	-CFLAGS "-include $(GEN_DIR)/V$(DESIGN)-debug.csrc/VZynqShim.h" \
	-LDFLAGS "$(LDFLAGS) -lemul -lpthread" \
	-o $@ $< $(TESTBENCH) $(emul_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN)-debug.csrc -f VZynqShim.mk

verilator: $(OUT_DIR)/V$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
verilator-debug: $(OUT_DIR)/V$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Compile VCS emulation binary
VCS := vcs -full64
VCS_FLAGS := -quiet -timescale=1ns/1ps +v2k +rad +vcs+initreg+random +vcs+lic+wait \
	-e vcs_main -cpp $(CXX) -CFLAGS "$(CXXFLAGS) -I$(VCS_HOME)/include -DVCS" \
	+define+CLOCK_PERIOD=$(CLOCK_PERIOD)

$(OUT_DIR)/$(DESIGN): $(GEN_DIR)/ZynqShim.v $(v_dir)/emul.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN).csrc +vc+list \
	-CFLAGS "-include $(GEN_DIR)/$(DESIGN)-const.h" -LDFLAGS "$(LDFLAGS) -lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 2, $^) $(TESTBENCH) $(emul_cc) $(word 4, $^)

$(OUT_DIR)/$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(v_dir)/emul.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-debug.csrc +vc+list -debug_pp +define+DEBUG \
	-CFLAGS "-include $(GEN_DIR)/$(DESIGN)-const.h" -LDFLAGS "$(LDFLAGS) -lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 2, $^) $(TESTBENCH) $(emul_cc) $(word 4, $^)

vcs: $(OUT_DIR)/$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
vcs-debug: $(OUT_DIR)/$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Compile VCS replay binary
replay_files := sample replay_vpi
replay_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(replay_files) replay))
replay_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(replay_files)))

$(OUT_DIR)/$(DESIGN)-replay: $(GEN_DIR)/$(DESIGN).v $(v_dir)/replay.v $(util_dir)/libreplay.a $(replay_cc) $(replay_h)
	mkdir -p $(OUT_DIR)
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-replay.csrc \
	+define+VFRAG=\"$(GEN_DIR)/$(DESIGN).vfrag\" \
	-debug_pp +vpi -P $(r_dir)/vpi.tab -LDFLAGS "$(LDFLAGS) -lreplay" \
	-o $@ $< $(word 2, $^) $(replay_cc) $(word 3, $^)

vcs-replay: $(OUT_DIR)/$(DESIGN)-replay

clean:
	rm -rf $(util_dir)/*.o $(util_dir)/*.a

.PHONY: zynq verilator verilator-debug vcs vcs-debug vcs-replay clean
