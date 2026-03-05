# See LICENSE for license details.

##########################
# Midas-Level Sim Recipes#
##########################

vcs_args = +vcs+initreg+0 +vcs+initmem+0

# copied from fasedtests/metasim.mk
TIMEOUT_CYCLES = 1000000000

vcs_args = +vcs+initreg+0 +vcs+initmem+0

# Arguments used only at a particular simulation abstraction
MIDAS_LEVEL_SIM_ARGS ?= +max-cycles=$(TIMEOUT_CYCLES)
FPGA_LEVEL_SIM_ARGS ?=

# PointerChaser requires a custom memory initialization
ifeq ($(DESIGN),PointerChaser)
LOADMEM ?= $(GENERATED_DIR)/mem_init.hex
$(LOADMEM): src/main/resources/midasexamples/generate_memory_init.py
	$< --output_file $@
else
LOADMEM ?=
endif

# These are from MIDAS examples
loadmem = $(if $(LOADMEM),+loadmem=$(abspath $(LOADMEM)),)
benchmark = $(notdir $(basename $(if $(LOADMEM),$(notdir $(LOADMEM)),$(DESIGN))))
logfile = $(if $(LOGFILE),$(abspath $(LOGFILE)),$(OUTPUT_DIR)/$(benchmark).$1.out)
waveform = $(if $(WAVEFORM),$(abspath $(WAVEFORM)),$(OUTPUT_DIR)/$(benchmark).$1.$2)
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

run-verilator-debug run-verilator: run-verilator% : $(GENERATED_DIR)/V$(DESIGN)% $(LOADMEM)
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) $(COMMON_SIM_ARGS) $(ARGS) \
	$(loadmem) \
	$(if $(findstring debug,$@),+waveformfile=$(call waveform,verilator,vcd),) 2> $(call logfile,verilator)

run-vcs run-vcs-post-synth run-vcs-debug run-vcs-post-synth-debug: run-vcs%: $(GENERATED_DIR)/$(DESIGN)% $(LOADMEM)
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) \
		$(vcs_args) \
		$(COMMON_SIM_ARGS) \
		$(ARGS) \
		$(loadmem) \
		$(if $(findstring debug,$@),+fsdbfile=$(call waveform,vcs$(<:$(GENERATED_DIR)/$(DESIGN)%=%),fsdb),) \
		2> $(call logfile,vcs$(<:$(GENERATED_DIR)/$(DESIGN)%=%))

.PHONY: run-xsim
run-xsim: $(xsim)
	cd $(dir $<) && ./$(notdir $<)  $(COMMON_SIM_ARGS) $(FPGA_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS)

