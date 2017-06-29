# compile DRAMSim2
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.$(o), $(wildcard $(midas_dir)/dramsim2/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.$(o): $(midas_dir)/dramsim2/%.cpp
ifneq ($(CXX),cl)
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<
else
	$(CXX) $(CXXFLAGS) /DNO_STORAGE /DNO_OUTPUT /Dmain=nomain /c /Fo$(call path,$@) $(call path,$<)
endif

# Compile utility code
lib_files := biguint mm mm_dramsim2 umi umi_dramsim2 $(if $(filter $(CXX),cl),,midas_context)
lib_cc    := $(addprefix $(util_dir)/, $(addsuffix .cc, $(lib_files)))
lib_o     := $(addprefix $(GEN_DIR)/, $(addsuffix .$(o), $(lib_files)))

$(lib_o): $(GEN_DIR)/%.$(o): $(util_dir)/%.cc
ifneq ($(CXX),cl)
	$(CXX) $(CXXFLAGS) -c -o $@ $<
else
	$(CXX) $(CXXFLAGS) /c /Fo$(call path,$@) $(call path,$<)
endif

ifneq ($(CXX),cl)
lib       := $(GEN_DIR)/libmidas.a
else
lib       := $(GEN_DIR)/midas.lib
endif

$(lib): $(lib_o) $(dramsim_o)
ifneq ($(CXX),cl)
	$(AR) rcs $@ $^
else
	$(AR) -OUT:$(call path,$@) $(foreach f, $^, $(call path,$f))
endif
