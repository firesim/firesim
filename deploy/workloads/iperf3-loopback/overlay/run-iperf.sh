#!/bin/sh

iperf3 -s &> /root/server.log &

sleep 0.1

start_packets=$(cat /sys/class/net/lo/statistics/tx_packets)
start_dropped=$(cat /sys/class/net/lo/statistics/tx_dropped)

iperf3 -t 5 -c 127.0.0.1

end_packets=$(cat /sys/class/net/lo/statistics/tx_packets)
end_dropped=$(cat /sys/class/net/lo/statistics/tx_dropped)

echo "Packets sent: $(($end_packets-$start_packets))"
echo "Packets dropped: $(($end_dropped-$start_dropped))"

pkill -SIGINT iperf3

sleep 1.0
