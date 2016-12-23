# compile DRAMSim2
ifneq ($(CXX),cl)
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.o, $(wildcard $(midas_dir)/dramsim2/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.o: $(midas_dir)/dramsim2/%.cpp
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<
else
dramsim_o := $(foreach f, \
		$(patsubst %.cpp, %.obj, $(wildcard $(midas_dir)/dramsim2/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.obj: $(midas_dir)/dramsim2/%.cpp
	$(CXX) $(CXXFLAGS) /DNO_STORAGE /DNO_OUTPUT /Dmain=nomain /c /Fo$(call path,$@) $(call path,$<)
endif

# Compile utility code
lib_files := biguint mm mm_dramsim2 $(if $(filter $(CXX),cl),,context)
lib_cc    := $(addprefix $(util_dir)/, $(addsuffix .cc, $(lib_files)))
ifneq ($(CXX),cl)
lib_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .o, $(lib_files)))

$(lib_o): $(GEN_DIR)/%.o: $(util_dir)/%.cc
	$(CXX) $(CXXFLAGS) -c -o $@ $<

lib := $(GEN_DIR)/libmidas.a
$(lib): $(lib_o) $(dramsim_o)
	$(AR) rcs $@ $^
else
lib_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .obj, $(lib_files)))

$(lib_o): $(GEN_DIR)/%.obj: $(util_dir)/%.cc
	$(CXX) $(CXXFLAGS) /c /Fo$(call path,$@) $(call path,$<)

lib := $(GEN_DIR)/midas.lib
$(lib): $(lib_o) $(dramsim_o)
	$(AR) -OUT:$(call path,$@) $(foreach f, $^, $(call path,$f))
endif
