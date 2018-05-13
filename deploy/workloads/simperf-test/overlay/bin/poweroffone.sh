#!/bin/bash

echo "poweroff"

# we assume we're running on 192.168.0.2 and talking to 192.168.0.3

HNAME=$(ifconfig eth0 | awk '/inet addr/{print substr($2,6)}')
echo $HNAME

if [ "$HNAME" == "192.168.0.2" ]; then
    echo "this node is powering off."
    poweroff -f
else
    echo "this node is idling."
    while true; do sleep 1000; done
fi
