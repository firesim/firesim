#!/bin/bash

echo "J0 Running:"
echo "j0 : run" >> /root/j0_output

echo "Global output:"
cat /root/runOutput

echo "Job output:"
cat /root/j0_output

sync
poweroff -f
