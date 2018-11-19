#!/bin/bash
set -x

echo "I only ran the first time!" >> /root/runOutput
cat /root/runOutput

sync
poweroff -f
