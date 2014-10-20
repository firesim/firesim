basedir := $(abspath .)
srcdir  := $(basedir)/src/main/scala/designs
csrcdir := $(basedir)/csrc
tutdir  := $(basedir)/tutorial/examples
gendir  := $(basedir)/generated
logdir  := $(basedir)/logs
resdir  := $(basedir)/results
zeddir  := $(basedir)/fpga-zynq/zedboard
bitstream := fpga-images-zedboard/boot.bin
designs := GCD Parity Stack Router Risc RiscSRAM FIR2D\
	ShiftRegister ResetShiftRegister EnableShiftRegister MemorySearch
VPATH   := $(srcdir):$(gendir):$(tutdir):$(gendir)

C_FLAGS := --targetDir $(gendir) --genHarness --compile --test --vcd --debug
V_FLAGS := $(C_FLAGS) --v
CXX := arm-xilinx-linux-gnueabi-g++
CXXFLAGS := -static -O2

default: GCD

cpp := $(addsuffix Shim.cpp, $(designs))
v   := $(addsuffix Shim.v,   $(designs))
fpga := $(addsuffix -fpga, $(designs))
driver := $(addsuffix -zedborad, $(designs))

$(designs): %: %Shim.v %-fpga %-zedborad

$(cpp): %Shim.cpp: %.scala 
	mkdir -p $(logdir)
	sbt "run $(basename $@) $(C_FLAGS)" | tee $(logdir)/$@.out

$(v)  : %Shim.v: %.scala 
	mkdir -p $(logdir) $(resdir)
	sbt "run $(basename $@) $(V_FLAGS)" | tee $(logdir)/$@.out
	cd $(gendir); cp $*.io.map $*.chain.map $(resdir)

$(fpga): %-fpga: %Shim.v
	mkdir -p $(resdir)
	cd $(zeddir); make clean; make $(bitstream) DESIGN=$*; cp $(bitstream) $(resdir)

$(driver): %-zedborad: $(csrcdir)/%.cc $(csrcdir)/debug_api.cc $(csrcdir)/debug_api.h
	mkdir -p $(resdir)
	cd $(resdir); $(CXX) $(CXXFLAGS) $^ -o $@

clean:
	rm -rf $(gendir) $(logdir) $(resdir) 

cleanall:
	rm -rf project/target target
	$(MAKE) -C chisel clean	

.PHONY: all cpp v *-fpga clean cleanall
