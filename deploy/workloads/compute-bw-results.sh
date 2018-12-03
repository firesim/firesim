#!/bin/bash

WORKDIR=$1
BW_CALC=/home/centos/firesim/scripts/net-bw-calc.py

for switch in $WORKDIR/switch*
do
    result=$(echo $switch | sed -e 's/switch/result/').csv
    python $BW_CALC --timestep=10000 $switch/switchlog $result &
done

wait
