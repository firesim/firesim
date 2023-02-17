# See LICENSE for license details.

##########################
# Midas-Level Sim Recipes#
##########################

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
