#!/bin/bash
echo "J0 Initializing:"
echo "j0 : init" >> /root/j0_output

echo "Global output:"
cat /root/runOutput

echo "Job output:"
cat /root/j0_output

sync
poweroff -f
