#!/bin/bash
echo "j1 : init" >> /root/j1_output
cat /root/runOutput
cat /root/j1_Output

sync
poweroff -f
