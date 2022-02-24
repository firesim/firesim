
all: switch

switch: switch.cc baseport.h shmemport.h flit.h socketport.h switchconfig.h
	g++ -g3 -O3 -std=gnu++11 -o switch switch.cc -fopenmp -lrt

runswitch:
	echo "removing old /dev/shm/*"
	rm -rf /dev/shm/*
	./switch

clean:
	rm -rf switch*-build/
	rm -rf /dev/shm/*
