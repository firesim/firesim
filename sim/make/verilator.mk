# See LICENSE for license details.

####################################
# Verilator MIDAS-Level Simulators #
####################################

VERILATOR_CXXOPTS ?= -O0
VERILATOR_MAKEFLAGS ?= -j8 VM_PARALLEL_BUILDS=1

verilator = $(GENERATED_DIR)/V$(DESIGN)
verilator_debug = $(GENERATED_DIR)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VERILATOR_CXXOPTS) -D RTLSIM
$(verilator) $(verilator_debug): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

$(verilator): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir) VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"

$(verilator_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) $(VERILATOR_MAKEFLAGS) -C $(simif_dir) verilator-debug PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir) VERILATOR_FLAGS="$(EXTRA_VERILATOR_FLAGS)"

.PHONY: verilator verilator-debug
verilator: $(verilator)
verilator-debug: $(verilator_debug)
