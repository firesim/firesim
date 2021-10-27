# Compile DRAMSim2
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.o, $(wildcard $(dramsim_dir)/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.o: $(dramsim_dir)/%.cpp
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<

ext_files := mm mm_dramsim2
ext_cc    := $(addprefix $(testchip_csrc_dir)/, $(addsuffix .cc, $(ext_files)))
ext_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(ext_files)))

$(ext_o): $(GEN_DIR)/%.o: $(testchip_csrc_dir)/%.cc
	$(CXX) $(CXXFLAGS) -c -o $@ $<

lib       := $(GEN_DIR)/libmidas.a

$(lib): $(ext_o) $(dramsim_o)
	$(AR) rcs $@ $^
