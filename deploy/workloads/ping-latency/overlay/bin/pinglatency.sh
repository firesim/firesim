#!/bin/bash

echo "running ping"

# we assume we're running on 192.168.0.2 and talking to 192.168.0.3

# ignore the first ping, because it includes ARP, etc.
ping -c 1 192.168.0.3 &> /dev/null

for i in {1..100..1}
do
    ping -c 1 192.168.0.3
done
