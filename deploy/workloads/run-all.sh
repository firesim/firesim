#!/usr/bin/env bash

# run the bw test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT

# first, run bw-test because it's hacky and patches the switches
./run-bw-test.sh withlaunch

# next, launch the rest in screens
screen -S memcached -d -m ./run-memcached-thread-imbalance.sh withlaunch
screen -S simperf-latency -d -m ./run-simperf-test-latency.sh withlaunch
screen -S simperf-scale -d -m ./run-simperf-test-scale.sh withlaunch
screen -S ping-latency -d -m ./run-ping-latency.sh withlaunch
