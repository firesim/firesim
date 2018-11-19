#!/bin/bash
echo "J1 Running:"
echo "j1 : run" >> /root/j1_output

echo "Global output:"
cat /root/runOutput

echo "Job output:"
cat /root/j1_output

sync
poweroff -f
