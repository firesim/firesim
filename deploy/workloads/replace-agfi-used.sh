#!/usr/bin/env bash

yamls=(bw-test-config.yaml memcached-thread-imbalance-config.yaml ping-latency-config.yaml simperf-test-latency-config.yaml simperf-test-scale-config-continue.yaml simperf-test-scale-config.yaml)

OLDAGFI="defaultserver=quadcore_nic_ddr3_llc4M"
NEWAGFI="defaultserver=firesim_quadcore_nic_ddr3_llc4mb"

for i in "${yamls[@]}"
do
	sed -i "s/$OLDAGFI/$NEWAGFI/g" $i
done
