# See LICENSE for license details.

$(FIRRTL_FILE) $(ANNO_FILE): $(TARGET_CP)
	@mkdir -p $(@D)
	cd $(base_dir) && java -cp $$(cat $(TARGET_CP)) chipyard.Generator \
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--legacy-configs $(TARGET_CONFIG_PACKAGE):$(TARGET_CONFIG)

#######################################
# Setup Extra Verilator Compile Flags #
#######################################

## default flags added for cva6
CVA6_VERILATOR_FLAGS = \
	--unroll-count 256 \
	-Werror-PINMISSING \
	-Werror-IMPLICIT \
	-Wno-fatal \
	-Wno-PINCONNECTEMPTY \
	-Wno-ASSIGNDLY \
	-Wno-DECLFILENAME \
	-Wno-UNUSED \
	-Wno-UNOPTFLAT \
	-Wno-BLKANDNBLK \
	-Wno-style \
	-Wall

# normal flags used for midas builds (that are incompatible with cva6)
DEFAULT_MIDAS_VERILATOR_FLAGS = \
	--assert

# AJG: this must be evaluated after verilog generation to work (hence the =)
EXTRA_VERILATOR_FLAGS = \
	$(shell if ! grep -iq "module.*cva6" $(simulator_verilog); then echo "$(DEFAULT_MIDAS_VERILATOR_FLAGS)"; else echo "$(CVA6_VERILATOR_FLAGS)"; fi)
