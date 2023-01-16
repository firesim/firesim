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

$(f1): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-I$(platforms_dir)/f1/aws-fpga/sdk/userspace/include
# We will copy shared libs into same directory as driver on runhost, so add $ORIGIN to rpath
$(f1): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' -L /usr/local/lib64 -lfpga_mgmt

# Compile Driver
$(f1): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=f1 PLATFORM=f1 \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		DRIVER="$(DRIVER_CC)" \
		TOP_DIR=$(chipyard_dir)

$(vitis): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) $(DRIVER_CXXOPTS) \
	-idirafter ${CONDA_PREFIX}/include -idirafter /usr/include -idirafter $(XILINX_XRT)/include
# -ldl needed for Ubuntu 20.04 systems (is backwards compatible with U18.04 systems)
$(vitis): export LDFLAGS := $(LDFLAGS) $(common_ld_flags) -Wl,-rpath='$$$$ORIGIN' \
	-L${CONDA_PREFIX}/lib -Wl,-rpath-link=/usr/lib/x86_64-linux-gnu -L$(XILINX_XRT)/lib -luuid -lxrt_coreutil -ldl


# Compile Driver
$(vitis): $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=vitis PLATFORM=vitis \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		DRIVER="$(DRIVER_CC)" \
		TOP_DIR=$(chipyard_dir)

tags: $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .
