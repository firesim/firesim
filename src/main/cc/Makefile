base_dir = $(abspath .)
util_dir = $(base_dir)/utils
v_dir = $(abspath ../verilog)
r_dir = $(abspath ../resources)

override CXXFLAGS := $(CXXFLAGS) -std=c++11 -Wall -I$(base_dir)/dramsim2

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

override CXXFLAGS += -I$(base_dir) -I$(util_dir) $(if $(TESTBENCH),-I$(dir $(TESTBENCH)))
override LDFLAGS := $(LDFLAGS) -L$(util_dir) -lstdc++ 

zynq_files := sample simif simif_zynq
zynq_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(zynq_files)))
zynq_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(zynq_files)))

$(OUT_DIR)/$(DESIGN)-zynq: $(TESTBENCH) $(GEN_DIR)/$(DESIGN)-const.h $(util_dir)/biguint.cc $(util_dir)/biguint.h $(zynq_cc) $(zynq_h)
	mkdir -p $(OUT_DIR)
	$(CXX) $(CXXFLAGS) -include $(word 2, $^) -o $@ $< $(word 3, $^) $(zynq_cc)

zynq: $(OUT_DIR)/$(DESIGN)-zynq


$(OUT_DIR)/dramsim2_ini: $(r_dir)/dramsim2_ini
	ln -sf $< $@

$(OUT_DIR)/$(DESIGN).chain:
	if [ -a $(GEN_DIR)/$(DESIGN).chain ]; then \
	  cp -u $(GEN_DIR)/$(DESIGN).chain $@; \
	fi

emul_files := sample simif simif_emul
emul_h := $(addprefix $(base_dir)/, $(addsuffix .h, $(emul_files)))
emul_cc := $(addprefix $(base_dir)/, $(addsuffix .cc, $(emul_files)))

# Compile verilator emulation binary
VERILATOR ?= verilator --cc --exe
override VERILATOR_FLAGS := $(VERILATOR_FLAGS) --assert -Wno-STMTDLY -O3 -CFLAGS "$(CXXFLAGS)" -LDFLAGS "$(LDFLAGS)"

$(OUT_DIR)/V$(DESIGN): $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/V$(DESIGN).csrc ]; then \
	  rm -rf $(GEN_DIR)/V$(DESIGN).csrc; \
	fi
	$(VERILATOR) $(VERILATOR_FLAGS) --top-module ZynqShim -Mdir $(GEN_DIR)/V$(DESIGN).csrc \
	-CFLAGS "-include $(word 2, $^) -include $(GEN_DIR)/V$(DESIGN).csrc/VZynqShim.h" \
	-LDFLAGS "-lemul -lpthread" -o $@ $< $(TESTBENCH) $(emul_cc)
	$(MAKE) -C $(GEN_DIR)/V$(DESIGN).csrc -f VZynqShim.mk

$(OUT_DIR)/V$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
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
override VCS_FLAGS := $(VCS_FLAGS) -quiet -timescale=1ns/1ps +v2k +rad +vcs+initreg+random +vcs+lic+wait \
	-e vcs_main -cpp $(CXX) +define+CLOCK_PERIOD=$(CLOCK_PERIOD) \
	-CFLAGS "$(CXXFLAGS) -I$(VCS_HOME)/include -DVCS" -LDFLAGS "$(LDFLAGS)"

$(OUT_DIR)/$(DESIGN): $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(v_dir)/emul.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/$(DESIGN).csrc ]; then \
	  rm -rf $(GEN_DIR)/$(DESIGN).csrc; \
	fi
	if [ -a $(OUT_DIR)/$(DESIGN).daidir ]; then \
	  rm -rf $(OUT_DIR)/$(DESIGN).daidir; \
	fi
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN).csrc +vc+list \
	-CFLAGS "-include $(word 2, $^)" -LDFLAGS "-lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 3, $^) $(TESTBENCH) $(emul_cc) $(word 5, $^)

$(OUT_DIR)/$(DESIGN)-debug: $(GEN_DIR)/ZynqShim.v $(GEN_DIR)/$(DESIGN)-const.h $(v_dir)/emul.v $(TESTBENCH) $(util_dir)/libemul.a $(emul_cc) $(emul_h)
	mkdir -p $(OUT_DIR)
	if [ -a $(GEN_DIR)/$(DESIGN)-debug.csrc ]; then \
	  rm -rf $(GEN_DIR)/$(DESIGN)-debug.csrc; \
	fi
	if [ -a $(OUT_DIR)/$(DESIGN)-debug.daidir ]; then \
	  rm -rf $(OUT_DIR)/$(DESIGN)-debug.daidir; \
	fi
	$(VCS) $(VCS_FLAGS) -Mdir=$(GEN_DIR)/$(DESIGN)-debug.csrc +vc+list -debug_pp +define+DEBUG \
	-CFLAGS "-include $(word 2, $^)" -LDFLAGS "-lemul -lpthread" \
	-o $@ $< $(GEN_DIR)/$(DESIGN)-const.vh $(word 3, $^) $(TESTBENCH) $(emul_cc) $(word 5, $^)

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
	+define+VFRAG=\"$(GEN_DIR)/$(DESIGN).vfrag\" \
	-debug_pp +vpi -P $(r_dir)/vpi.tab -LDFLAGS "$(LDFLAGS) -lreplay" \
	-o $@ $< $(word 2, $^) $(replay_cc) $(word 3, $^)

vcs-replay: $(OUT_DIR)/$(DESIGN)-replay

clean:
	rm -rf $(util_dir)/*.o $(util_dir)/*.a

.PHONY: zynq verilator verilator-debug vcs vcs-debug vcs-replay clean
