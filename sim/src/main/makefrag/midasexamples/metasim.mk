# See LICENSE for license details.

##########################
# Midas-Level Sim Recipes#
##########################

vcs_args = +vcs+initreg+0 +vcs+initmem+0

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

run-verilator-debug run-verilator: run-verilator% : $(GENERATED_DIR)/V$(DESIGN)% $(LOADMEM)
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) $(COMMON_SIM_ARGS) $(ARGS) \
	$(loadmem) \
	+waveform=$(call waveform,verilator,vcd) 2> $(call logfile,verilator)

run-vcs run-vcs-debug: run-vcs%: $(GENERATED_DIR)/$(DESIGN)% $(LOADMEM)
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) $(vcs_args) $(COMMON_SIM_ARGS) $(ARGS) \
	$(loadmem) \
	+waveform=$(call waveform,vcs,vpd) 2> $(call logfile,vcs)
