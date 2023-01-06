# See LICENSE for license details.

##############################
# VCS MIDAS-Level Simulators #
##############################

VCS_CXXOPTS ?= -O2

vcs = $(GENERATED_DIR)/$(DESIGN)
vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug

$(vcs) $(vcs_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VCS_CXXOPTS) -I$(VCS_HOME)/include -D RTLSIM
# VCS can mangle the ordering of libraries and object files at link time such
# that some valid dependencies are pruned when --as-needed is set.
# Conservatively set --no-as-needed in case --as-needed is defined in LDFLAGS.
$(vcs) $(vcs_debug): export LDFLAGS := $(LDFLAGS) -Wl,--no-as-needed $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

$(vcs): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) -C $(simif_dir) vcs PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

$(vcs_debug): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h) $(simulator_verilog)
	$(MAKE) -C $(simif_dir) vcs-debug PLATFORM=$(PLATFORM) DRIVER_NAME=$(DESIGN) GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
	GEN_DIR=$(GENERATED_DIR) DRIVER="$(DRIVER_CC)" TOP_DIR=$(chipyard_dir)

.PHONY: vcs vcs-debug
vcs: $(vcs)
vcs-debug: $(vcs_debug)

