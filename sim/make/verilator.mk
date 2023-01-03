# See LICENSE for license details.

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_MAKEFLAGS ?= -j8 VM_PARALLEL_BUILDS=1

verilator = $(GENERATED_DIR)/V$(DESIGN)

.PHONY: verilator
verilator: $(verilator)
$(verilator): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)" \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"

verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

.PHONY: verilator-debug
verilator-debug: $(verilator-debug)
$(verilator_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator-debug \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)" \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"
