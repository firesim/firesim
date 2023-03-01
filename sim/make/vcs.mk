# See LICENSE for license details.

################################################################################
# VCS MIDAS-Level Simulators
################################################################################

VCS_CXXOPTS ?= -O2

vcs = $(GENERATED_DIR)/$(DESIGN)
vcs_debug = $(GENERATED_DIR)/$(DESIGN)-debug
vcs_post_synth = $(GENERATED_DIR)/$(DESIGN)-post-synth
vcs_post_synth_debug = $(GENERATED_DIR)/$(DESIGN)-post-synth-debug

$(vcs) $(vcs_post_synth) $(vcs_debug) $(vcs_post_synth_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(VCS_CXXOPTS) -I$(VCS_HOME)/include -D RTLSIM
# VCS can mangle the ordering of libraries and object files at link time such
# that some valid dependencies are pruned when --as-needed is set.
# Conservatively set --no-as-needed in case --as-needed is defined in LDFLAGS.
$(vcs) $(vcs_post_synth) $(vcs_debug) $(vcs_post_synth_debug): export LDFLAGS := $(LDFLAGS) -Wl,--no-as-needed $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN'

vcs_driver_deps := $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)

define make_vcs
	$(MAKE) -C $(simif_dir) $(1) \
		PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(GENERATED_DIR) \
		DRIVER="$(DRIVER_CC)" \
		TOP_DIR=$(chipyard_dir) \
		DESIGN_V=$(2)
endef

$(vcs): $(vcs_driver_deps) $(simulator_verilog)
	$(call make_vcs, vcs,$(simulator_verilog))

$(vcs_debug): $(vcs_driver_deps) $(simulator_verilog)
	$(call make_vcs, vcs-debug,$(simulator_verilog))

$(vcs_post_synth): $(vcs_driver_deps) $(POST_SYNTH_RTL)
	$(call make_vcs, vcs-post-synth,$(POST_SYNTH_RTL))

$(vcs_post_synth_debug): $(vcs_driver_deps) $(POST_SYNTH_RTL)
	$(call make_vcs, vcs-post-synth-debug,$(POST_SYNTH_RTL))

.PHONY: vcs vcs-debug vcs-post-synth  vcs-post-synth-f1-debug
vcs: $(vcs)
vcs-debug: $(vcs_debug)
vcs-post-synth: $(vcs_post_synth)
vcs-post-synth-debug: $(vcs_post_synth_debug)
