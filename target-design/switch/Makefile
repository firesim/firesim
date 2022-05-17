
LDLIBS += -lrt
CPPFLAGS += -fopenmp
CXXFLAGS += -g3 -O3 -std=gnu++11
# we will copy shared libs to same directory on runner. put $ORIGIN in rpath
LDFLAGS += -Wl,-rpath='$$ORIGIN'

all: switch

switch: switch.cc baseport.h shmemport.h flit.h socketport.h switchconfig.h
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(LDFLAGS) -o switch switch.cc $(LDLIBS)

runswitch:
	echo "removing old /dev/shm/*"
	rm -rf /dev/shm/*
	./switch

clean:
	rm -rf switch*-build/
	rm -rf /dev/shm/*
