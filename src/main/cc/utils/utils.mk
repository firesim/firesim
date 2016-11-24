# compile DRAMSim2
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.o, $(wildcard $(base_dir)/dramsim2/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.o: $(base_dir)/dramsim2/%.cpp
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<

# Compile utility code
lib_files := biguint context mm mm_dramsim2
lib_cc    := $(addprefix $(util_dir)/, $(addsuffix .cc, $(lib_files)))
lib_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(lib_files)))
lib       := $(GEN_DIR)/libmidas.a

$(lib_o): $(GEN_DIR)/%.o: $(util_dir)/%.cc
	$(CXX) $(CXXFLAGS) -c -o $@ $<

$(lib): $(lib_o) $(dramsim_o)
	$(AR) rcs $@ $^
