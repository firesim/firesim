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

XSIM_CXX_FLAGS := \
	$(TARGET_CXX_FLAGS) \
	$(common_cxx_flags) \
	-DSIMULATION_XSIM \
	-DNO_MAIN

XSIM_LD_FLAGS := \
	$(TARGET_LD_FLAGS) \
	$(common_ld_flags)

$(xsim): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) f1 MAIN=f1_xsim PLATFORM=f1 \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		OUT_DIR=$(GENERATED_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		PLATFORM_CXX_FLAGS="$(XSIM_CXX_FLAGS)" \
		PLATFORM_LD_FLAGS="$(XSIM_LD_FLAGS)" \
		DRIVER_LIBS="$(DRIVER_LIBS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"

.PHONY: xsim
xsim: $(xsim)
