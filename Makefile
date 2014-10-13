srcdir  := src/main/scala
gendir  := generated
tutdir  := tutorial/examples
designs := GCD
VPATH   := $(srcdir):$(gendir):$(tutdir)

C_FLAGS := --targetDir $(gendir) --genHarness --compile --test --vcd --debug
V_FLAGS := $(C_FLAGS) --v

all : cpp v
cpp : $(addsuffix Shim.cpp, $(designs))
v   : $(addsuffix Shim.v,   $(designs))

%Shim.cpp: %.scala 
	sbt "run $(basename $@) $(C_FLAGS)" | tee $@.out

%Shim.v: %.scala 
	sbt "run $(basename $@) $(V_FLAGS)" | tee $@.out

clean:
	rm -rf $(gendir) *.out

cleanall:
	rm -rf project/target target
	$(MAKE) -C chisel clean	

.PHONY: all cpp v clean cleanall
