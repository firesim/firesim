#!/bin/bash
echo "j0 : run" >> /root/j0_output
cat /root/runOutput
cat /root/j0_output

sync
poweroff -f
