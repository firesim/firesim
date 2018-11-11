#!/bin/bash

if [ ! -f /root/runOutput ]; then
  echo "ERROR: The overlay didn't get applied!"
fi

echo "Job 1 ran!" >> /root/runOutput
cat /root/runOutput

sync
poweroff -f
