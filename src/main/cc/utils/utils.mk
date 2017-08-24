# Compile DRAMSim2
dramsim_o := $(foreach f, \
                $(patsubst %.cpp, %.$(o), $(wildcard $(midas_dir)/dramsim2/*.cpp)), \
                $(GEN_DIR)/$(notdir $(f)))
$(dramsim_o): $(GEN_DIR)/%.$(o): $(midas_dir)/dramsim2/%.cpp
ifneq ($(CXX),cl)
	$(CXX) $(CXXFLAGS) -DNO_STORAGE -DNO_OUTPUT -Dmain=nomain -c -o $@ $<
else
	$(CXX) $(CXXFLAGS) /DNO_STORAGE /DNO_OUTPUT /Dmain=nomain /c /Fo$(call path,$@) $(call path,$<)
endif

# Compile gmp
ifneq ($(CXX),cl)
GMP_VERSION ?= 6.1.2
gmp_src_dir := $(midas_dir)/gmp-$(GMP_VERSION)
emul_gmp_build_dir := $(gmp_src_dir)/build-emul
emul_gmp_install_dir := $(gmp_src_dir)/install-emul
emul_gmp := $(emul_gmp_install_dir)/lib/libgmp.so
platform_gmp_build_dir := $(gmp_src_dir)/build-$(PLATFORM)
platform_gmp_install_dir := $(gmp_src_dir)/install-$(PLATFORM)
platform_gmp := $(platform_gmp_install_dir)/lib/libgmp.a

$(midas_dir)/gmp-$(GMP_VERSION).tar.bz2:
	wget https://gmplib.org/download/gmp/gmp-$(GMP_VERSION).tar.bz2

$(gmp_src_dir): $(midas_dir)/gmp-$(GMP_VERSION).tar.bz2
	tar -xf $<

$(emul_gmp): $(gmp_src_dir)
	mkdir -p $(emul_gmp_build_dir)
	cd $(emul_gmp_build_dir) && \
	../configure --prefix=$(emul_gmp_install_dir) && \
	$(MAKE) && $(MAKE) install

ifeq ($(PLATFORM),zynq)
host = arm-xilinx-linux-gnueabi
endif

$(platform_gmp): $(gmp_src_dir)
	mkdir -p $(platform_gmp_build_dir)
	cd $(platform_gmp_build_dir) && \
	../configure --prefix=$(platform_gmp_install_dir) --host=$(host) && \
	$(MAKE) && $(MAKE) install
endif

# Compile utility code
lib_files := mm mm_dramsim2 $(if $(filter $(CXX),cl),,midas_context)
ifeq ($(PLATFORM),catapult)
	lib_files += umi umi_dramsim2
endif
ifeq ($(CXX),cl)
	lib_files += biguint
endif
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
