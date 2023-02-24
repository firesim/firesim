# See LICENSE for license details.

################################################################
# SW RTL Simulation Args -- for MIDAS- & FPGA-level Simulation #
################################################################
TIMEOUT_CYCLES = 1000000000

vcs_args = +vcs+initreg+0 +vcs+initmem+0

# Arguments used only at a particular simulation abstraction
MIDAS_LEVEL_SIM_ARGS ?= +max-cycles=$(TIMEOUT_CYCLES)
FPGA_LEVEL_SIM_ARGS ?=

############################################
# Midas-Level Simulation Execution Recipes #
############################################

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug
vcs = $(GENERATED_DIR)/$(DESIGN)
vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

logfile = $(if $(LOGFILE),$(abspath $(LOGFILE)),$(OUTPUT_DIR)/$1.out)
waveform = $(if $(WAVEFORM),$(abspath $(WAVEFORM)),$(OUTPUT_DIR)/$1.$2)

run-verilator: $(verilator)
	mkdir -p $(OUTPUT_DIR)
	cd $(<D) && ./$(<F) $(COMMON_SIM_ARGS) $(MIDAS_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS) 2> $(call logfile,verilator)

run-verilator-debug: $(verilator_debug)
	mkdir -p $(OUTPUT_DIR)
	cd $(<D) && ./$(<F) $(COMMON_SIM_ARGS) $(MIDAS_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS) +waveform=$(call waveform,verilator,vcd) 2> $(call logfile,verilator)

run-vcs run-vcs-post-synth run-vcs-debug run-vcs-post-synth-debug: run-vcs%: $(GENERATED_DIR)/$(DESIGN)% $(LOADMEM)
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) \
		$(vcs_args) \
		$(COMMON_SIM_ARGS) \
		$(ARGS) \
		$(loadmem) \
		+waveform=$(call waveform,vcs$(<:$(GENERATED_DIR)/$(DESIGN)%=%),vpd) \
		2>&1 | tee $(call logfile,vcs$(<:$(GENERATED_DIR)/$(DESIGN)%=%))

.PHONY: run-xsim
run-xsim: $(xsim)
	cd $(dir $<) && ./$(notdir $<)  $(COMMON_SIM_ARGS) $(FPGA_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS)
