#!/usr/bin/env bash

inis=(bw-test-config.ini memcached-thread-imbalance-config.ini ping-latency-config.ini simperf-test-latency-config.ini simperf-test-scale-config-continue.ini simperf-test-scale-config.ini)

OLDAGFI="defaultserver=quadcore-nic-ddr3-llc4M"
NEWAGFI="defaultserver=firesim-quadcore-nic-ddr3-llc4mb"

for i in "${inis[@]}"
do
	sed -i "s/$OLDAGFI/$NEWAGFI/g" $i
done
