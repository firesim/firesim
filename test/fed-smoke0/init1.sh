#!/bin/bash
echo "J1 Initializing:"
echo "j1 : init" >> /root/j1_output

echo "Global output:"
cat /root/runOutput

echo "Job output:"
cat /root/j1_output

sync
poweroff -f
