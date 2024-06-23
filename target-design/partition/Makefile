
LDLIBS += -lrt
CPPFLAGS += -fopenmp
CXXFLAGS += -g3 -O3 -std=gnu++11
# we will copy shared libs to same directory on runner. put $ORIGIN in rpath
LDFLAGS += -Wl,-rpath='$$ORIGIN'

all: partitionpipe

partitionpipe: partitionpipe.cc basepipe.h shmempipe.h partitionconfig.h
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(LDFLAGS) -o partitionpipe partitionpipe.cc $(LDLIBS)

runpartitionpipe:
	echo "removing old /dev/shm/*"
	rm -rf /dev/shm/pipe*
	./partitionpipe

clean:
	rm -rf partitionpipe*-build/
	rm -rf /dev/shm/pipe*
