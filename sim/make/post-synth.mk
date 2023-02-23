# See LICENSE for license details.

STRATEGY ?= BASIC
FREQUENCY ?= 30


################################################################################
# Post-synthesis RTL generation
################################################################################

POST_SYNTH_RTL := $(GENERATED_DIR)/verilog.sv
POST_SYNTH_XDC := $(GENERATED_DIR)/$(BASE_FILE_NAME).post-synth.xdc

$(POST_SYNTH_XDC): $(simulator_xdc)
	sed 's#firesim_top/##g' $(simulator_xdc) > $(POST_SYNTH_XDC)

$(POST_SYNTH_RTL): $(simulator_verilog) $(POST_SYNTH_XDC) scripts/synth_fpga.tcl
	 cd $(GENERATED_DIR) && time vivado \
			-mode batch -nojournal \
			-source $(firesim_base_dir)/scripts/synth_fpga.tcl \
			-tclargs $(STRATEGY) $(FREQUENCY) $@ $(POST_SYNTH_XDC) $(simulator_verilog)

.PHONY: post-synth-rtl
post-synth-rtl: $(POST_SYNTH_RTL)
