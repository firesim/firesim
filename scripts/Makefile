CC=gcc
CXX=g++
CFLAGS=-Wall -O2 -I$(RISCV)/include
CXXFLAGS=$(CFLAGS) -std=gnu++11
LDFLAGS=-L$(RISCV)/lib -lelf -ldwarf

TRACERV_DIR=$(abspath ../sim/firesim-lib/src/main/cc/bridges/tracerv)
TRACERV_SOURCES=$(wildcard $(TRACERV_DIR)/*.cc)

all: format-trace trace-tracker

format-trace: format-trace.c
	$(CC) $(CFLAGS) $< $(LDFLAGS) -o $@

trace-tracker: $(TRACERV_SOURCES)
	$(CXX) $(CXXFLAGS) -DTRACERV_TOP_MAIN $^ $(LDFLAGS) -o $@
