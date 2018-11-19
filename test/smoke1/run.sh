#!/bin/bash

if [ ! -f /root/runOutput ]; then
  echo "ERROR: The overlay didn't get applied!"
fi

echo "Ran at runtime!" >> /root/runOutput
cat /root/runOutput

sync
poweroff -f
