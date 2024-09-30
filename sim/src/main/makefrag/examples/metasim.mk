# See LICENSE for license details.

###########################
# Midas-Level Sim Recipes:
#
# midas provides make recipes to build simulator binaries for a set of SW RTL
# simulators. these make targets use those recipe outputs to create targets
# to run the simulations.
###########################

# terminate simulation after N cycles
COMMON_SIM_ARGS += +max-cycles=10000

run-verilator-debug run-verilator: run-verilator% : $(GENERATED_DIR)/V$(DESIGN)%
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) $(COMMON_SIM_ARGS)

run-vcs run-vcs-post-synth run-vcs-debug run-vcs-post-synth-debug: run-vcs%: $(GENERATED_DIR)/$(DESIGN)%
	mkdir -p $(OUTPUT_DIR)
	cd $(GENERATED_DIR) && ./$(notdir $<) +vcs+initreg+0 +vcs+initmem+0 $(COMMON_SIM_ARGS)
