base_dir = $(abspath .)
util_dir = $(base_dir)/utils
v_dir = $(abspath ../verilog)
r_dir = $(abspath ../resources)

override CXXFLAGS := $(CXXFLAGS) -std=c++11 -Wall -I$(base_dir)/dramsim2

include $(util_dir)/Makefrag

########################################################################
# Parameters:
# 1) PLATFORM: FPGA platform board(by default zynq)
# 2) DESIGN: Target design of strober
# 3) GEN_DIR: Directory for generated source code
# 4) OUT_DIR: Directory for binary files (by default GEN_DIR)
# 5) TESTBENCH: C++ testbench written by user (not necessary for replay)
# 6) CLOCK_PERIOD(optional): clock period of tests
########################################################################
PLATFORM ?= zynq
OUT_DIR ?= $(GEN_DIR)
CLOCK_PERIOD ?= 0.5

override CXXFLAGS += -I$(base_dir) -I$(util_dir) $(foreach t, $(TESTBENCH), -I$(dir $(t))) -DPLATFORM=$(PLATFORM)
override LDFLAGS := $(LDFLAGS) -L$(util_dir) -lstdc++ 

testbench_h = $(foreach t, $(TESTBENCH), $(wildcard $(dir $(t))/*.h))

$(PLATFORM)_files := sample simif simif_$(PLATFORM)
$(PLATFORM)_h := $(addprefix $(base_dir)/, $(addsuffix .h, $($(PLATFORM)_files)))
$(PLATFORM)_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $($(PLATFORM)_files)))

$(OUT_DIR)/$(DESIGN)-$(PLATFORM): $(GEN_DIR)/$(DESIGN)-const.h $(util_dir)/biguint.cc $(util_dir)/biguint.h $(TESTBENCH) $(testbench_h) $($(PLATFORM)_cc) $($(PLATFORM)_h)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -include $< -o $@ $(word 2, $^) $(TESTBENCH) $($(PLATFORM)_cc)

$(PLATFORM): $(OUT_DIR)/$(DESIGN)-$(PLATFORM)


$(OUT_DIR)/dramsim2_ini: $(r_dir)/dramsim2_ini
	ln -sf $< $@

$(OUT_DIR)/$(DESIGN).chain:
	if [ -a $(GEN_DIR)/$(DESIGN).chain ]; then \
	  cp -u $(GEN_DIR)/$(DESIGN).chain $@; \
	fi

emul_files := sample simif simif_emul
emul_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(emul_files)))
emul_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(emul_files)))
emul_v := $(v_dir)/emul_$(PLATFORM).v

# Compile verilator emulation binary
VERILATOR ?= verilator --cc --exe
override VERILATOR_FLAGS := --assert -Wno-STMTDLY -O3 -CFLAGS "$(CXXFLAGS)" -LDFLAGS "$(LDFLAGS)" $(VERILATOR_FLAGS)

$(OUT_DIR)/V$(DESIGN): $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(util_dir)/libemul.a $(TESTBENCH) $(testbench_h) $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/V$(DESIGN).csrc ]; then \
	  rm -rf $(GEN_DIR)/V$(DESIGN).csrc; \
	fi
	$(VERILATOR) $(VERILATOR_FLAGS) --top-module ZynqShim -Mdir $(GEN_DIR)/V$(DESIGN).csrc \
	-CFLAGS "-include $(word 2, $^) -include $(GEN_DIR)/V$(DESIGN).csrc/VZynqShim.h" \
	-LDFLAGS "-lemul -lpthread" -o $@ $< $(TESTBENCH) $(emul_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN).csrc -f VZynqShim.mk

$(OUT_DIR)/V$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(util_dir)/libemul.a $(TESTBENCH) $(testbench_h) $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/V$(DESIGN)-debug.csrc ]; then \
	  rm -rf $(GEN_DIR)/V$(DESIGN)-debug.csrc; \
	fi
	$(VERILATOR) $(VERILATOR_FLAGS) --trace --top-module ZynqShim -Mdir $(GEN_DIR)/V$(DESIGN)-debug.csrc \
	-CFLAGS "-include $(word 2, $^) -include $(GEN_DIR)/V$(DESIGN)-debug.csrc/VZynqShim.h" \
	-LDFLAGS "-lemul -lpthread" -o $@ $< $(TESTBENCH) $(emul_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN)-debug.csrc -f VZynqShim.mk

verilator: $(OUT_DIR)/V$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
verilator-debug: $(OUT_DIR)/V$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Compile VCS emulation binary
VCS ?= vcs -full64
override VCS_FLAGS := -quiet -timescale=1ns/1ps +v2k +rad +vcs+initreg+random +vcs+lic+wait -debug_pp \
	-e vcs_main -cpp $(CXX) -CFLAGS "$(CXXFLAGS) -DVCS -I$(VCS_HOME)/include" -LDFLAGS "$(LDFLAGS)" \
	+define+CLOCK_PERIOD=$(CLOCK_PERIOD) \
	+define+RANDOMIZE_MEM_INIT \
	+define+RANDOMIZE_REG_INIT \
	+define+RANDOMIZE_GARBAGE_ASSIGN \
	+define+RANDOMIZE_INVALID_ASSIGN \
	$(VCS_FLAGS)

$(OUT_DIR)/$(DESIGN): $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(emul_v) $(util_dir)/libemul.a $(TESTBENCH) $(testbench_h) $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/$(DESIGN).csrc ]; then \
	  rm -rf $(GEN_DIR)/$(DESIGN).csrc; \
	fi
	if [ -a $(OUT_DIR)/$(DESIGN).daidir ]; then \
	  rm -rf $(OUT_DIR)/$(DESIGN).daidir; \
	fi
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN).csrc +vc+list \
	+define+STOP_COND=!emul.reset \
	-CFLAGS "-include $(word 2, $^)" -LDFLAGS "-lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 3, $^) $(word 4, $^) $(TESTBENCH) $(emul_cc)

$(OUT_DIR)/$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(emul_v) $(util_dir)/libemul.a $(TESTBENCH) $(testbench_h) $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/$(DESIGN)-debug.csrc ]; then \
	  rm -rf $(GEN_DIR)/$(DESIGN)-debug.csrc; \
	fi
	if [ -a $(OUT_DIR)/$(DESIGN)-debug.daidir ]; then \
	  rm -rf $(OUT_DIR)/$(DESIGN)-debug.daidir; \
	fi
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-debug.csrc +vc+list \
	+define+STOP_COND=!emul.reset +define+DEBUG \
	-CFLAGS "-include $(word 2, $^)" -LDFLAGS "-lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 3, $^) $(word 4, $^) $(TESTBENCH) $(emul_cc)

vcs: $(OUT_DIR)/$(DESIGN) $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini
vcs-debug: $(OUT_DIR)/$(DESIGN)-debug $(OUT_DIR)/$(DESIGN).chain $(OUT_DIR)/dramsim2_ini

# Compile VCS replay binary
replay_files := sample replay_vpi
replay_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(replay_files) replay))
replay_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(replay_files)))

$(OUT_DIR)/$(DESIGN)-replay: $(GEN_DIR)/$(DESIGN).v $(v_dir)/replay.v $(util_dir)/libreplay.a $(replay_cc) $(replay_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/$(DESIGN)-replay.csrc ]; then \
	  rm -rf $(GEN_DIR)/$(DESIGN)-replay.csrc; \
	fi
	if [ -a $(OUT_DIR)/$(DESIGN)-replay.daidir ]; then \
	  rm -rf $(OUT_DIR)/$(DESIGN)-replay.daidir; \
	fi
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-replay.csrc \
	+define+STOP_COND=!replay.reset +define+VFRAG=\"$(GEN_DIR)/$(DESIGN).vfrag\" \
	+vpi -P $(r_dir)/vpi.tab -LDFLAGS "$(LDFLAGS) -lreplay" \
	-o $@ $< $(word 2, $^) $(replay_cc) $(word 3, $^)

vcs-replay: $(OUT_DIR)/$(DESIGN)-replay

clean:
	rm -rf $(util_dir)/*.o $(util_dir)/*.a

.PHONY: $(PLATFORM) verilator verilator-debug vcs vcs-debug vcs-replay clean
