SRCDIR = .
CXX = g++
CC = gcc
CXXFLAGS = -I $(SRCDIR) -Wall -c -std=c++11 -g
CFLAGS= -I $(SRCDIR) -Wall -c
LDFLAGS =
# lib/ini files are in C, not C++
SSD_C_SUBDIRS = lib/ini
SSD_CXX_SUBDIRS = util log dram pal/old pal ftl icl hil hil/nvme
C_SRCS = $(foreach dir,$(SSD_C_SUBDIRS),$(wildcard $(dir)/*.c))
CXX_SRCS = $(foreach dir,$(SSD_CXX_SUBDIRS),$(wildcard $(dir)/*.cc))
C_OBJS = $(patsubst %.c,%.o,$(C_SRCS))
CXX_OBJS = $(patsubst %.cc,%.o,$(CXX_SRCS))
OBJS = $(C_OBJS) $(CXX_OBJS)

all: $(C_SRCS) $(CXX_SRCS) ssd

%.o: %.c	
	$(CC) $(CFLAGS) $< -o $@

%.o: %.cc
	$(CXX) $(CXXFLAGS) $< -o $@

ssd: $(OBJS)
	# udaya: keep linker commented out until i figure how to integrate this with firesim
	$(CXX) $< -o $@

.PHONY: clean
clean:
	rm -f ssd $(OBJS)
	# remove any created binaries also
