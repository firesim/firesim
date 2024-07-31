# See LICENSE for license details.

############################
# Master Simulation Driver #
############################
DRIVER_CXXOPTS ?= -O2

platforms_dir := $(abspath $(firesim_base_dir)/../platforms)

$(PLATFORM) = $(OUTPUT_DIR)/$(DESIGN)-$(PLATFORM)
$(PLATFORM): $($(PLATFORM))

.PHONY: driver
driver: $(PLATFORM)


# these compilation flags are setup for centos7-only (what AWS FPGA supports)
$(f1): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-I$(platforms_dir)/f1/aws-fpga/sdk/userspace/include
# We will copy shared libs into same directory as driver on runhost, so add $ORIGIN to rpath ($$ORIGIN when given on the shell)
$(f1): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' -L /usr/local/lib64 -lfpga_mgmt -lz

# Compile Driver
$(f1): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=$(PLATFORM) PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		DRIVER="$(DRIVER_CC)"

# macro to create a driver that was built only with a conda environment (no leakage of host's environment)
# $1 - platform name (i.e. xilinx_alveo_u250)
# note: $$'s are used to escape the $ when the define is 1st called (i.e. so $$(var) turns into $(var) later)
# note: $$$$$$$$'s is used to create $$ORIGIN when run on the shell
define built_within_conda_only_driver_compilation_rules
$$($1): export CXXFLAGS := $$(CXXFLAGS) $$(common_cxx_flags) $$(DRIVER_CXXOPTS)
$$($1): export LDFLAGS := $$(LDFLAGS) $$(common_ld_flags) -Wl,-rpath='$$$$$$$$ORIGIN'
$$($1): $$(header) $$(DRIVER_CC) $$(DRIVER_H) $$(midas_cc) $$(midas_h)
	mkdir -p $$(OUTPUT_DIR)/build
	cp $$(header) $$(OUTPUT_DIR)/build/
	$$(MAKE) -C $$(simif_dir) driver MAIN=$$(PLATFORM) PLATFORM=$$(PLATFORM) \
		DRIVER_NAME=$$(DESIGN) \
		GEN_FILE_BASENAME=$$(BASE_FILE_NAME) \
		GEN_DIR=$$(OUTPUT_DIR)/build \
		OUT_DIR=$$(OUTPUT_DIR) \
		DRIVER="$$(DRIVER_CC)"
endef

$(eval $(call built_within_conda_only_driver_compilation_rules,xilinx_alveo_u250))
$(eval $(call built_within_conda_only_driver_compilation_rules,xilinx_alveo_u280))
$(eval $(call built_within_conda_only_driver_compilation_rules,xilinx_alveo_u200))
$(eval $(call built_within_conda_only_driver_compilation_rules,xilinx_vcu118))
$(eval $(call built_within_conda_only_driver_compilation_rules,rhsresearch_nitefury_ii))

# these compilation flags are only guaranteed to work for ubuntu 20.04/18.04 (other OS's are not supported since vitis is experimental)
$(vitis): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-idirafter ${CONDA_PREFIX}/include -idirafter /usr/include -idirafter $(XILINX_XRT)/include
# -ldl needed for Ubuntu 20.04 systems (is backwards compatible with U18.04 systems)
$(vitis): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' \
	-L${CONDA_PREFIX}/lib -Wl,-rpath-link=/usr/lib/x86_64-linux-gnu -L/usr/lib/x86_64-linux-gnu -L$(XILINX_XRT)/lib -luuid -lxrt_coreutil -ldl -lz

# Compile Driver
$(vitis): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=$(PLATFORM) PLATFORM=$(PLATFORM) \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		DRIVER="$(DRIVER_CC)"

tags: $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .
