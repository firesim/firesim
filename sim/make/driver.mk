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

F1_CXX_FLAGS := \
	$(common_cxx_flags) \
	$(DRIVER_CXXOPTS) \
	-I$(platforms_dir)/f1/aws-fpga/sdk/userspace/include

F1_LD_FLAGS := \
	$(common_ld_flags) \
	-L/usr/local/lib64 \
	-lfpga_mgmt

# Compile Driver
$(f1): $(header) $(DRIVER_CC) $(DRIVER_H) $(DRIVER_LIBS) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=f1 PLATFORM=f1 \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		PLATFORM_CXX_FLAGS="$(F1_CXX_FLAGS)" \
		PLATFORM_LD_FLAGS="$(F1_LD_FLAGS)" \
		DRIVER_LIBS="$(DRIVER_LIBS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"

VITIS_CXX_FLAGS := \
	$(common_cxx_flags) \
	$(DRIVER_CXXOPTS) \
	-idirafter ${CONDA_PREFIX}/include \
	-idirafter /usr/include \
	-idirafter $(XILINX_XRT)/include

VITIS_LD_FLAGS := \
	$(common_ld_flags) \
	-L${CONDA_PREFIX}/lib \
	-Wl,-rpath-link=/usr/lib/x86_64-linux-gnu \
	-L$(XILINX_XRT)/lib \
	-luuid \
	-lxrt_coreutil

# Compile Driver
$(vitis): $(header) $(DRIVER_CC) $(DRIVER_H) $(DRIVER_LIBS) $(midas_cc) $(midas_h)
	mkdir -p $(OUTPUT_DIR)/build
	cp $(header) $(OUTPUT_DIR)/build/
	$(MAKE) -C $(simif_dir) driver MAIN=vitis PLATFORM=vitis \
		DRIVER_NAME=$(DESIGN) \
		GEN_FILE_BASENAME=$(BASE_FILE_NAME) \
		GEN_DIR=$(OUTPUT_DIR)/build \
		OUT_DIR=$(OUTPUT_DIR) \
		TOP_DIR=$(chipyard_dir) \
		BASE_DIR=$(base_dir) \
		DRIVER_CC="$(DRIVER_CC)" \
		DRIVER_CXX_FLAGS="$(DRIVER_CXX_FLAGS)" \
		PLATFORM_CXX_FLAGS="$(VITIS_CXX_FLAGS)" \
		PLATFORM_LD_FLAGS="$(VITIS_LD_FLAGS)" \
		DRIVER_LIBS="$(DRIVER_LIBS)" \
		TARGET_LD_FLAGS="$(TARGET_LD_FLAGS)"

tags: $(header) $(DRIVER_CC) $(DRIVER_H) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .
