# See LICENSE for license details.

################################################################################
# XCELIUM MIDAS-Level Simulators
################################################################################

XCELIUM_CXXOPTS ?= -O2

xcelium = $(GENERATED_DIR)/X$(DESIGN)

$(xcelium): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(XCELIUM_CXXOPTS) -I$(XCELIUM_HOME)/include -D RTLSIM
# XCELIUM can mangle the ordering of libraries and object files at link time such
# that some valid dependencies are pruned when --as-needed is set.
# Conservatively set --no-as-needed in case --as-needed is defined in LDFLAGS.
# -Wl,--no-as-needed
$(xcelium): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

xcelium_driver_deps := $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)

define make_xcelium
	$(MAKE) -C $(simif_dir) $(1) \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		DRIVER="$(DRIVER_CC)" \
		TOP_DIR=$(chipyard_dir) \
		DESIGN_V=$(2)
endef

$(xcelium): $(xcelium_driver_deps) $(simulator_verilog)
	$(call make_xcelium, xcelium,$(simulator_verilog))

.PHONY: xcelium
xcelium: $(xcelium)
