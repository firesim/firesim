# See LICENSE for license details.

STRATEGY ?= QUICK
FREQUENCY ?= 30


################################################################################
# Post-synthesis RTL generation
################################################################################

POST_SYNTH_RTL := $(GENERATED_DIR)/verilog.sv
POST_SYNTH_DESIGN_XDC := $(GENERATED_DIR)/$(BASE_FILE_NAME).post-synth.xdc
POST_SYNTH_XDC := $(firesim_base_dir)/scripts/synth_fpga.xdc

$(POST_SYNTH_DESIGN_XDC): $(simulator_xdc)
	sed 's#firesim_top/top/##g' $(simulator_xdc) > $(POST_SYNTH_DESIGN_XDC)

$(POST_SYNTH_RTL): $(firesim_base_dir)/scripts/synth_fpga.tcl $(POST_SYNTH_XDC) $(POST_SYNTH_DESIGN_XDC)  $(simulator_verilog)
	 cd $(GENERATED_DIR) && time vivado \
			-mode batch -nojournal \
			-source $(firesim_base_dir)/scripts/synth_fpga.tcl \
			-tclargs $(STRATEGY) $(FREQUENCY) $@ $(wordlist 2,4,$^)

.PHONY: post-synth-rtl
post-synth-rtl: $(POST_SYNTH_RTL)
