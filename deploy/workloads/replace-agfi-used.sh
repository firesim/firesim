#!/usr/bin/env bash

yamls=(bw-test-config.yaml memcached-thread-imbalance-config.yaml ping-latency-config.yaml simperf-test-latency-config.yaml simperf-test-scale-config-continue.yaml simperf-test-scale-config.yaml)

OLDAGFI="defaultserver=quadcore-nic-ddr3-llc4M"
NEWAGFI="defaultserver=firesim-quadcore-nic-ddr3-llc4mb"

for i in "${yamls[@]}"
do
	sed -i "s/$OLDAGFI/$NEWAGFI/g" $i
done
