basedir := $(abspath .)
srcdir  := $(basedir)/src/main/scala/designs
tutdir  := $(basedir)/tutorial/examples
minidir := $(basedir)/riscv-mini/src/main/scala/designs
csrcdir := $(basedir)/csrc
gendir  := $(basedir)/generated
logdir  := $(basedir)/logs
resdir  := $(basedir)/results
zeddir  := $(basedir)/fpga-zynq/zedboard
bitstream := fpga-images-zedboard/boot.bin
designs := GCD Parity Stack Router Risc RiscSRAM FIR2D \
	ShiftRegister ResetShiftRegister EnableShiftRegister MemorySearch
VPATH   := $(srcdir):$(tutdir):$(minidir):$(gendir):$(logdir)
memgen  := $(basedir)/scripts/fpga_mem_gen
C_FLAGS := --targetDir $(gendir) --genHarness --compile --test --vcd --debug
V_FLAGS := $(C_FLAGS) --v
FPGA_FLAGS := --targetDir $(gendir) --backend fpga
CXX := arm-xilinx-linux-gnueabi-g++
CXXFLAGS := -static -O2

default: GCD

cpp     := $(addsuffix Shim.cpp, $(designs))
harness := $(addsuffix Shim-harness.v, $(designs))
v       := $(addsuffix Shim.v, $(designs) Core Tile)
fpga    := $(addsuffix -fpga, $(designs) Core Tile)
driver  := $(addsuffix -zedboard, $(designs) Core Tile)

replay_cpp := $(addsuffix .cpp, $(designs))
replay_v   := $(addsuffix .v,   $(designs))

cpp: $(cpp)
harness: $(harness)
replay-cpp: $(replay_cpp)
replay-v: $(replay_v)

$(designs) Core Tile: %: %Shim.v %-fpga %-zedboard

$(cpp): %Shim.cpp: %.scala 
	mkdir -p $(logdir)
	sbt "run $(basename $@) $(C_FLAGS)" | tee $(logdir)/$@.out

$(harness): %Shim-harness.v: %.scala 
	mkdir -p $(logdir) $(resdir)
	sbt "run $*Shim $(V_FLAGS)" | tee $(logdir)/$*Shim.v.out

$(replay_cpp): %.cpp: %.scala %Shim.cpp
	mkdir -p $(logdir)
	sbt "run $(basename $@) $(C_FLAGS)" | tee $(logdir)/$@.out

$(replay_v): %.v: %.scala %Shim-harness.v
	mkdir -p $(logdir)
	sbt "run $(basename $@) $(V_FLAGS)" | tee $(logdir)/$@.out

$(v): %Shim.v: %.scala
	rm -rf $(gendir)/$@
	mkdir -p $(logdir) $(resdir)
	sbt "run $(basename $@) $(FPGA_FLAGS)"
	if [ -a $(gendir)/$(basename $@).conf ]; then \
          $(memgen) $(gendir)/$(basename $@).conf >> $(gendir)/$(basename $@).v; \
        fi
	cd $(gendir) ; cp $*.io.map $*.chain.map $(resdir)

$(fpga): %-fpga: %Shim.v
	cd $(zeddir); make clean; make $(bitstream) DESIGN=$*; cp $(bitstream) $(resdir)

$(driver): %-zedboard: $(csrcdir)/%.cc $(csrcdir)/debug_api.cc $(csrcdir)/debug_api.h
	mkdir -p $(resdir)
	cd $(resdir); $(CXX) $(CXXFLAGS) $^ -o $@

tests_isa_dir  := $(basedir)/riscv-mini/tests/isa
timeout_cycles := 10000
include riscv-mini/Makefrag-sim

core_asm_c = $(addprefix Core., $(addsuffix .cpp.out, $(asm_p_tests)))
$(core_asm_c): Core.%.cpp.out: $(tests_isa_dir)/%.hex $(minidir)/Core.scala
	mkdir -p $(logdir)
	cd $(basedir) ; sbt "run CoreShim $(C_FLAGS) +loadmem=$< +max-cycles=$(timeout_cycles)" \
        | tee $(logdir)/$(notdir $@)
core_asm_c: $(core_asm_c)
	@echo; perl -ne 'print " [$$1] $$ARGV \t$$2\n" if /\*{3}(.{8})\*{3}(.*)/' \
	$(addprefix $(logdir)/, $(core_asm_c)); echo;

core_asm_v = $(addprefix Core., $(addsuffix .v.out, $(asm_p_tests)))
$(core_asm_v): Core.%.v.out: $(tests_isa_dir)/%.hex $(minidir)/Core.scala
	mkdir -p $(logdir)
	cd $(basedir) ; sbt "run CoreShim $(V_FLAGS) +loadmem=$< +max-cycles=$(timeout_cycles)" \
        | tee $(logdir)/$(notdir $@)
core_asm_v: $(core_asm_v)
	@echo; perl -ne 'print " [$$1] $$ARGV \t$$2\n" if /\*{3}(.{8})\*{3}(.*)/' \
	$(addprefix $(logdir)/, $(core_asm_v)); echo;

tile_asm_c = $(addprefix Tile., $(addsuffix .cpp.out, $(asm_p_tests)))
$(tile_asm_c): Tile.%.cpp.out: $(tests_isa_dir)/%.hex $(minidir)/Tile.scala
	mkdir -p $(logdir)
	cd $(basedir) ; sbt "run TileShim $(C_FLAGS) +loadmem=$< +max-cycles=$(timeout_cycles)" \
        | tee $(logdir)/$(notdir $@)
tile_asm_c: $(tile_asm_c)
	@echo; perl -ne 'print " [$$1] $$ARGV \t$$2\n" if /\*{3}(.{8})\*{3}(.*)/' \
	$(addprefix $(logdir)/, $(tile_asm_c)); echo;

tile_asm_v = $(addprefix Tile., $(addsuffix .v.out, $(asm_p_tests)))
$(tile_asm_v): Tile.%.v.out: $(tests_isa_dir)/%.hex $(minidir)/Tile.scala
	mkdir -p $(logdir)
	cd $(basedir) ; sbt "run TileShim $(V_FLAGS) +loadmem=$< +max-cycles=$(timeout_cycles) +verbose" \
        | tee $(logdir)/$(notdir $@)
tile_asm_v: $(tile_asm_v)
	@echo; perl -ne 'print " [$$1] $$ARGV \t$$2\n" if /\*{3}(.{8})\*{3}(.*)/' \
	$(addprefix $(logdir)/, $(tile_asm_v)); echo;

clean:
	rm -rf $(gendir) $(logdir) $(resdir) 

cleanall:
	rm -rf project/target target
	$(MAKE) -C chisel clean	

.PHONY: all cpp v $(v) $(fpga) clean cleanall
