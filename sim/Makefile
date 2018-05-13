##################
#   Parameters   #
##################

# These point at the main class of the target's Chisel generator
PROJECT ?= firesim
# DESIGN ?= FireSim
# DESIGN ?= FireBoom
DESIGN ?= SupernodeTop

# These guide chisel elaboration of the target design specified above.
# See src/main/scala/SimConfigs.scala
TARGET_PROJECT ?= firesim
# TARGET_CONFIG ?= FireSimRocketChipConfig
# TARGET_CONFIG ?= FireSimBoomConfig
TARGET_CONFIG ?= SupernodeFireSimRocketChipConfig

# These guide chisel elaboration of simulation components by MIDAS, including models and widgets.
# See src/main/scala/SimConfigs.scala
PLATFORM ?= f1
PLATFORM_PROJECT ?= firesim
PLATFORM_CONFIG ?= FireSimConfig

STROBER ?=
DRIVER ?=
SAMPLE ?=

sample = $(if $(SAMPLE),$(abspath $(SAMPLE)),$(DESIGN).sample)
benchmark = $(notdir $(basename $(sample)))

# The desired RTL simulator. supported options: {vcs, verilator}
EMUL ?= verilator

default: compile

base_dir = $(abspath .)
simif_dir = $(base_dir)/midas/src/main/cc
driver_dir = $(base_dir)/src/main/cc

name_tuple = $(DESIGN)-$(TARGET_CONFIG)-$(PLATFORM_CONFIG)

generated_dir ?= $(base_dir)/generated-src/$(PLATFORM)/$(name_tuple)
output_dir    ?= $(base_dir)/output/$(PLATFORM)/$(name_tuple)

driver_h = $(shell find $(driver_dir) -name ".h")
midas_h  = $(shell find $(simif_dir) -name "*.h")
midas_cc = $(shell find $(simif_dir) -name "*.cc")
emul_cc  = $(addprefix $(driver_dir)/, $(addsuffix .cc, \
	firesim_top fesvr/firesim_tsi fesvr/firesim_fesvr endpoints/serial endpoints/uart endpoints/loadmem_m)) \
	$(addprefix $(driver_dir)/, $(addsuffix .cc, \
	firesim_f1 endpoints/simplenic endpoints/blockdev))


common_cxx_flags = -I$(RISCV)/include -Wno-unused-variable -ggdb3

SBT ?= sbt
SBT_FLAGS ?= -J-Xmx16G -J-Xss8M -J-XX:MaxPermSize=256M -J-XX:MaxMetaspaceSize=512M -J-XX:ReservedCodeCacheSize=1G
sbt:
	$(SBT) $(SBT_FLAGS)
test:
	$(SBT) $(SBT_FLAGS) test


src_path = src/main/scala
submodules = . midas firrtl \
	$(addprefix target-rtl/firechip/, testchipip icenet sifive-blocks \
	$(addprefix rocket-chip/, . hardfloat chisel3 chisel3/chiselFrontend))
chisel_srcs = $(foreach submodule,$(submodules),$(shell find $(base_dir)/$(submodule)/$(src_path) -name "*.scala"))

########################
# Timestamp & Patching #
########################

timestamps = $(addprefix $(base_dir)/, $(addsuffix .timestamp, firrtl rocket-chip sifive-blocks))

$(base_dir)/firrtl.timestamp: $(shell find $(base_dir)/firrtl/$(src_path) -name "*.scala")
	cd $(base_dir)/firrtl && sbt publishLocal
	touch $@

$(base_dir)/rocket-chip.timestamp: $(base_dir)/target-rtl/rocket-chip-no-async.patch
	cd $(base_dir)/target-rtl/firechip/rocket-chip && git apply $<
	touch $@

$(base_dir)/sifive-blocks.timestamp: $(base_dir)/target-rtl/sifive-blocks-uart-no-plusarg.patch
	cd $(base_dir)/target-rtl/firechip/sifive-blocks && git apply $<
	touch $@

########################
# RTL Generation       #
########################

verilog = $(generated_dir)/FPGATop.v
header = $(generated_dir)/$(DESIGN)-const.h

common_chisel_args = $(patsubst $(base_dir)/%,%,$(generated_dir)) $(PROJECT) $(DESIGN) $(TARGET_PROJECT) $(TARGET_CONFIG) $(PLATFORM_PROJECT) $(PLATFORM_CONFIG)

$(verilog) $(header): $(chisel_srcs) $(timestamps)
	mkdir -p $(@D)
	$(SBT) $(SBT_FLAGS) \
	"runMain $(PROJECT).FireSimGenerator $(if $(STROBER),strober,midas) $(common_chisel_args)"

verilog: $(verilog)
compile: $(verilog)

ifneq ($(filter run% %.run %.out %.vpd %.vcd,$(MAKECMDGOALS)),)
-include $(generated_dir)/$(PROJECT).d
endif

disasm := 2>
which_disasm := $(shell which spike-dasm 2> /dev/null)
ifneq ($(which_disasm),)
        disasm := 3>&1 1>&2 2>&3 | $(which_disasm) $(DISASM_EXTENSION) >
endif

####################################
# Runtime-Configuraiton Generation #
####################################
CONF_NAME ?= runtime.conf

.PHONY: conf
conf:
	mkdir -p $(generated_dir)
	$(SBT) $(SBT_FLAGS) \
	"runMain $(PROJECT).FireSimRuntimeConfGenerator $(CONF_NAME) $(common_chisel_args)"

##########################
# SW RTL Simulation Args #
##########################
TIMEOUT_CYCLES = 100000000
HOST_SETTINGS ?= +dramsim
SIM_RUNTIME_CONF ?= $(generated_dir)/$(CONF_NAME)
mem_model_args = $(shell cat $(SIM_RUNTIME_CONF))
SW_SIM_ARGS ?= $(HOST_SETTINGS) $(mem_model_args)

######################
# Verilator Emulation #
######################
verilator = $(generated_dir)/V$(DESIGN)
verilator_debug = $(generated_dir)/V$(DESIGN)-debug

$(verilator) $(verilator_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -D RTLSIM
$(verilator) $(verilator_debug): export LDFLAGS := $(LDFLAGS) -L$(RISCV)/lib -lrt -lfesvr -Wl,-rpath,$(RISCV)/lib

$(verilator): $(header) $(emul_cc) $(driver_h) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) verilator PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(generated_dir) DRIVER="$(emul_cc)"

$(verilator_debug): $(header) $(emul_cc) $(driver_h) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) verilator-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(generated_dir) DRIVER="$(emul_cc)"

verilator: $(verilator)
verilator-debug: $(verilator_debug)

######################
#   VCS Emulation    #
######################
vcs = $(generated_dir)/$(DESIGN)
vcs_debug = $(generated_dir)/$(DESIGN)-debug

$(vcs) $(vcs_debug): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -I$(VCS_HOME)/include -D RTLSIM
$(vcs) $(vcs_debug): export LDFLAGS := $(LDFLAGS) -L$(RISCV)/lib -lfesvr -lrt -Wl,-rpath,$(RISCV)/lib

$(vcs): $(header) $(emul_cc) $(driver_h) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) vcs PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(generated_dir) DRIVER="$(emul_cc)"

$(vcs_debug): $(header) $(emul_cc) $(driver_h) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) vcs-debug PLATFORM=$(PLATFORM) DESIGN=$(DESIGN) \
	GEN_DIR=$(generated_dir) DRIVER="$(emul_cc)"

vcs: $(vcs)
vcs-debug: $(vcs_debug)

###########################
#  RTL-Simulation Rules   #
###########################
$(output_dir)/%.run: $(output_dir)/% $(EMUL)
	cd $(dir $($(EMUL))) && \
	./$(notdir $($(EMUL))) $< +sample=$<.sample +max-cycles=$(TIMEOUT_CYCLES) $(SW_SIM_ARGS) \
	2> /dev/null 2> $@ && [ $$PIPESTATUS -eq 0 ]

$(output_dir)/%.out: $(output_dir)/% $(EMUL)
	cd $(dir $($(EMUL))) && \
	./$(notdir $($(EMUL))) $< +sample=$<.sample +max-cycles=$(TIMEOUT_CYCLES) $(SW_SIM_ARGS) \
	$(disasm) $@ && [ $$PIPESTATUS -eq 0 ]

$(output_dir)/%.vpd: $(output_dir)/% $(EMUL)-debug
	cd $(dir $($(EMUL)_debug)) && \
	./$(notdir $($(EMUL)_debug)) $< +sample=$<.sample +waveform=$@ +max-cycles=$(TIMEOUT_CYCLES) $(SW_SIM_ARGS) \
	$(disasm) $(patsubst %.vpd,%.out,$@) && [ $$PIPESTATUS -eq 0 ]

######################
#   Sample Replays   #
######################

replay_h = $(simif_dir)/sample/sample.h $(wildcard $(simif_dir)/replay/*.h)
replay_cc = $(simif_dir)/sample/sample.cc $(wildcard $(simif_dir)/replay/*.cc)

script_dir = $(base_dir)/midas/src/main/resources/replay
replay_sample = $(script_dir)/replay-samples.py
estimate_power = $(script_dir)/estimate-power.py

$(generated_dir)/$(DESIGN).v: $(chisel_srcs) $(timestamps) $(macro_lib)
	$(SBT) $(SBT_FLAGS) "run replay $(patsubst $(base_dir)/%,%,$(dir $@)) \
	$(PROJECT) $(DESIGN) $(TARGET_PROJECT) $(TARGET_CONFIG) $(PLATFORM_PROJECT) $(PLATFORM_CONFIG) $(macro_lib)"

compile-replay: $(generated_dir)/$(DESIGN).v

vcs_rtl = $(generated_dir)/$(DESIGN)-rtl
$(vcs_rtl): $(generated_dir)/$(DESIGN).v $(replay_cc) $(replay_h) $(replay_v)
	$(MAKE) -C $(simif_dir) $@ DESIGN=$(DESIGN) GEN_DIR=$(dir $<) REPLAY_BINARY=$@

vcs-rtl: $(vcs_rtl)

replay-rtl: $(vcs_rtl)
	cd $(dir $<) && ./$(notdir $<) +sample=$(sample) +verbose \
	+waveform=$(output_dir)/$(benchmark)-replay.vpd \
	$(disasm) $(output_dir)/$(benchmark)-replay.out && [ $$PIPESTATUS -eq 0 ]

######################
#   FPGA Simulation  #
######################
$(output_dir)/$(DESIGN).chain: $(verilog)
	mkdir -p $(output_dir)
	$(if $(wildcard $(generated_dir)/$(DESIGN).chain),cp $(generated_dir)/$(DESIGN).chain $@,)

$(PLATFORM) = $(output_dir)/$(DESIGN)-$(PLATFORM)
$(PLATFORM): $($(PLATFORM)) $(output_dir)/$(DESIGN).chain


fpga_dir = $(base_dir)/../platforms/$(PLATFORM)/aws-fpga
driver_cc = $(addprefix $(driver_dir)/, $(addsuffix .cc, \
		firesim_f1 endpoints/simplenic endpoints/blockdev firesim_top fesvr/firesim_tsi fesvr/firesim_fesvr endpoints/serial endpoints/uart endpoints/loadmem_m))

# TODO: Specify a main with a particular variable?
$(f1): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -I$(fpga_dir)/sdk/userspace/include
# Statically link libfesvr to make it easier to distribute drivers to f1 instances
$(f1): export LDFLAGS := $(LDFLAGS) -L$(RISCV)/lib -lfpga_mgmt -lrt \
	   -Wl,-Bstatic -lfesvr -Wl,-Bdynamic

# Compile Driver
$(f1): $(header) $(driver_cc) $(driver_h) $(midas_cc) $(midas_h) $(fesvr) $(spike) $(runtime_conf)
	mkdir -p $(output_dir)/build
	cp $(header) $(output_dir)/build/
	cp -f $(generated_dir)/$(CONF_NAME) $(output_dir)/runtime.conf
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(output_dir)/build OUT_DIR=$(output_dir) DRIVER="$(driver_cc)"

# Generate Bitstream
board_dir 	   := $(fpga_dir)/hdk/cl/developer_designs
fpga_work_dir  := $(board_dir)/cl_$(name_tuple)
build_dir      := $(fpga_work_dir)/build
verif_dir      := $(fpga_work_dir)/verif
fpga_v         := $(fpga_work_dir)/design/cl_firesim_generated.sv

$(fpga_work_dir)/stamp: $(shell find $(board_dir)/cl_firesim -name '*')
	mkdir -p $(@D)
	cp -rf $(board_dir)/cl_firesim -T $(fpga_work_dir)
	touch $@

$(fpga_v): $(verilog) $(fpga_work_dir)/stamp
	cp -f $< $@
	sed -i "s/\$$random/64'b0/g" $@
	sed -i 's/fatal/fatal(0, "")/g' $@

# Goes as far as setting up the build directory without running the cad job
# Used by the manager before passing a build to a remote machine
replace-rtl: $(fpga_v)
.PHONY: replace-rtl

$(base_dir)/scripts/checkpoints/$(target_sim_tuple): $(fpga_work_dir)/stamp
	mkdir -p $(@D)
	ln -sf $(build_dir)/checkpoints/to_aws $@

fpga: export CL_DIR := $(fpga_work_dir)
fpga: $(fpga_v) $(base_dir)/scripts/checkpoints/$(target_sim_tuple)
	cd $(build_dir)/scripts && ./aws_build_dcp_from_cl.sh -notify

# Run simulation
FPGA_SIM_ARGS ?= $(SW_SIM_ARGS)
run: $(PLATFORM)
	cd $(dir $(f1)) && sudo ./$(notdir $(f1)) $(FPGA_SIM_ARGS) \
	+sample=$(notdir $(SIM_BINARY)).sample $(abspath $(SIM_BINARY)) 2> $(notdir $(SIM_BINARY)).out

# Run XSIM DUT
xsim-dut: $(fpga_v) $(fpga_work_dir)/stamp
	cd $(verif_dir)/scripts && $(MAKE) C_TEST=test_firesim

# Compile XSIM Driver
xsim = $(generated_dir)/$(DESIGN)-$(PLATFORM)

$(xsim): export CXXFLAGS := $(CXXFLAGS) $(common_cxx_flags) -D SIMULATION_XSIM -D NO_MAIN
$(xsim): export LDFLAGS := $(LDFLAGS) -L$(RISCV)/lib -lfesvr -Wl,-rpath,$(RISCV)/lib
$(xsim): $(header) $(driver_cc) $(driver_h) $(midas_cc) $(midas_h)
	$(MAKE) -C $(simif_dir) f1 PLATFORM=f1 DESIGN=$(DESIGN) \
	GEN_DIR=$(generated_dir) OUT_DIR=$(generated_dir) DRIVER="$(driver_cc)"

xsim: $(xsim)

run-xsim: $(xsim)
	cd $(dir $<) && ./$(notdir $<) $(FPGA_SIM_ARGS) \
	+sample=$(notdir $(SIM_BINARY)).sample $(abspath $(SIM_BINARY))

cleanfpga:
	rm -rf $(fpga_work_dir)

mostlyclean:
	rm -rf $(verilator) $(verilator_debug) $(vcs) $(vcs_debug) $($(PLATFORM)) $(output_dir)

clean:
	rm -rf $(generated_dir) $(output_dir)

veryclean:
	rm -rf generated-src output

tags: $(header) $(driver_cc) $(driver_h) $(midas_cc) $(midas_h)
	ctags -R --exclude=@.ctagsignore .

.PHONY: sbt test
.PHONY: default verilog compile
.PHONY: verilator verilator-debug
.PHONY: vcs vcs-debug
.PHONY: run
.PHONY: xsim-dut xsim run-xsim
.PHONY: $(PLATFORM)-driver fpga
.PHONY: mostlyclean clean

.PRECIOUS: $(output_dir)/%.vpd $(output_dir)/%.out $(output_dir)/%.run
