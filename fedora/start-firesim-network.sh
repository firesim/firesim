#!/usr/bin/env bash

mac=$(ifconfig | grep -o "..:..:..:..:..:..")
macpref=$(echo $mac | cut -c 1-8 -)
echo "mac prefix:"
echo $macpref
case "$macpref" in
        "00:12:6D")
                echo "this looks like FireSim. starting network"
                ip link set eth0 up
                machigh=$(echo $mac | cut -c 13-14 -)
                maclow=$(echo $mac | cut -c 16-17 -)
                ip addr add 172.16.$((16#$machigh)).$((16#$maclow))/16 dev eth0
                ;;
        "52:54:00")
                echo "this looks like not FireSim. exiting"
                ;;
esac
