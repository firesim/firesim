#!/bin/bash
echo "j1 : run" >> /root/j1_output
cat /root/runOutput
cat /root/j1_output

sync
poweroff -f
