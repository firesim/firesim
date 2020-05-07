# Compile DRAMSim2
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.o, $(wildcard $(dramsim_dir)/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.o: $(dramsim_dir)/%.cpp
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<

ifeq ($(PLATFORM),zynq)
host = arm-xilinx-linux-gnueabi
endif

# Compile utility code
lib_files := $(if $(filter $(CXX),cl),,midas_context)
lib_cc    := $(addprefix $(util_dir)/, $(addsuffix .cc, $(lib_files)))
lib_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(lib_files)))

$(lib_o): $(GEN_DIR)/%.o: $(util_dir)/%.cc
	$(CXX) $(CXXFLAGS) -c -o $@ $<

ext_files := mm mm_dramsim2
ext_cc    := $(addprefix $(testchip_csrc_dir)/, $(addsuffix .cc, $(ext_files)))
ext_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(ext_files)))

$(ext_o): $(GEN_DIR)/%.o: $(testchip_csrc_dir)/%.cc
	$(CXX) $(CXXFLAGS) -c -o $@ $<

lib       := $(GEN_DIR)/libmidas.a

$(lib): $(lib_o) $(ext_o) $(dramsim_o)
	$(AR) rcs $@ $^
