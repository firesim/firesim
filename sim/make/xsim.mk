# See LICENSE for license details.

#############################
# FPGA-level RTL Simulation #
#############################

# Run XSIM DUT
.PHONY: xsim-dut
xsim-dut: replace-rtl $(fpga_work_dir)/stamp
	cd $(verif_dir)/scripts && $(MAKE) C_TEST=test_firesim

# Compile XSIM Driver #
xsim = $(GENERATED_DIR)/$(DESIGN)-$(PLATFORM)

$(xsim): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags)
$(xsim): export LDFLAGS := $(LDFLAGS) $(common_ld_flags)
$(xsim): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) driver MAIN=f1_xsim PLATFORM=f1 \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		OUT_DIR=$(GENERATED_DIR) \
		DRIVER="$(DRIVER_CC)" \
		TOP_DIR=$(chipyard_dir)

.PHONY: xsim
xsim: $(xsim)
