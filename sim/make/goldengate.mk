# See LICENSE for license details.

####################################
# Golden Gate Invocation           #
####################################

# Simulation memory map emitted by the MIDAS compiler
header := $(GENERATED_DIR)/$(BASE_FILE_NAME).const.h

# The midas-generated simulator RTL which will be baked into the FPGA shell project
simulator_verilog := $(GENERATED_DIR)/$(BASE_FILE_NAME).sv

# Pre-simulation-mapping annotations which includes all Bridge Annotations
# extracted used to generate new runtime configurations.
fame_annos := $(GENERATED_DIR)/post-bridge-extraction.json

.PHONY: verilog compile
verilog: $(simulator_verilog)
compile: $(simulator_verilog)

# empty recipe to help make understand multiple targets come from single recipe invocation
# without using the new (4.3) '&:' grouped targets see https://stackoverflow.com/a/41710495
.SECONDARY: $(simulator_verilog).intermediate
$(simulator_verilog) $(header) $(fame_annos): $(simulator_verilog).intermediate ;

# Disable FIRRTL 1.4 deduplication because it creates multiple failures
# Run the 1.3 version instead (checked-in). If dedup must be completely disabled,
# pass --no-legacy-dedup as well
$(simulator_verilog).intermediate: $(FIRRTL_FILE) $(ANNO_FILE) $(FIRESIM_MAIN_CP)
	java -cp $$(cat $(FIRESIM_MAIN_CP)) midas.stage.GoldenGateMain \
		-i $(FIRRTL_FILE) \
		-td $(GENERATED_DIR) \
		-faf $(ANNO_FILE) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG) \
		--output-filename-base $(BASE_FILE_NAME) \
		--no-dedup \

	grep -sh ^ $(GENERATED_DIR)/firrtl_black_box_resource_files.f | \
	xargs cat >> $(simulator_verilog) # Append blackboxes to FPGA wrapper, if any

####################################
# Runtime-Configuration Generation #
####################################

# This reads in the annotations from a generated target, elaborates a
# FASEDTimingModel if a BridgeAnnoation for one exists, and asks for user input
# to generate a runtime configuration that is compatible with the generated
# hardware (BridgeModule). Useful for modelling a memory system that differs from the default.
.PHONY: conf
conf: $(fame_annos)
	mkdir -p $(GENERATED_DIR)
	cd $(base_dir) && $(SBT) "project $(FIRESIM_SBT_PROJECT)" "runMain midas.stage.RuntimeConfigGeneratorMain \
		-td $(GENERATED_DIR) \
		-faf $(fame_annos) \
		-ggcp $(PLATFORM_CONFIG_PACKAGE) \
		-ggcs $(PLATFORM_CONFIG)"
