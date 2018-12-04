#!/bin/bash

WORKDIR=$1
BW_CALC=/home/centos/firesim/scripts/net-bw-calc.py
LAT_HISTO=/home/centos/firesim/scripts/net-latency-histo.py

for switch in $WORKDIR/switch*
do
    bw_result=$(echo $switch | sed -e 's/switch/bandwidth/').csv
    lat_result=$(echo $switch | sed -e 's/switch/latency/').csv
    python $BW_CALC --timestep=10000 $switch/switchlog $bw_result &
    python $LAT_HISTO $switch/switchlog $lat_result &
done

wait
