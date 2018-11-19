#!/bin/bash
echo "Global Running:"
echo "Global : run" >> /root/runOutput

echo "Global output:"
cat /root/runOutput

sync
poweroff -f
