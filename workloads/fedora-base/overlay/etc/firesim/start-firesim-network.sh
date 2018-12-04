#!/usr/bin/env bash

#mac=$(ifconfig | grep -o "..:..:..:..:..:..")
mac=$(cat /sys/class/net/eth0/address)
macpref=$(echo $mac | cut -c 1-8 -)
echo "mac prefix:"
echo $macpref
case "$macpref" in
        "00:12:6d")
                echo "this looks like FireSim. starting network"
                machigh=$(echo $mac | cut -c 13-14 -)
                maclow=$(echo $mac | cut -c 16-17 -)
                cp /etc/firesim/ifcfg-static /etc/sysconfig/network-scripts/ifcfg-eth0
                echo IPADDR=172.16.$((16#$machigh)).$((16#$maclow)) >> /etc/sysconfig/network-scripts/ifcfg-eth0
                ;;
        "52:54:00")
                echo "this looks like not FireSim. exiting"
            		cp /etc/firesim/ifcfg-dhcp /etc/sysconfig/network-scripts/ifcfg-eth0
                ;;
esac
