# See LICENSE for license details.

##############################
# VCS MIDAS-Level Simulators #
##############################

VCS_CXXOPTS ?= -O2


vcs = $(GENERATED_DIR)/$(DESIGN)

.PHONY: vcs
vcs: $(vcs)
$(vcs): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) vcs \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"

vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug

.PHONY: vcs-debug
vcs-debug: $(vcs-debug)
$(vcs_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) vcs-debug \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"
