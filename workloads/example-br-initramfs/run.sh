#!/bin/bash

if [ ! -f /root/runOutput ]; then
  echo "ERROR: The overlay didn't get applied!"
fi

echo "I ran!" >> /root/runOutput
cat /root/runOutput

sync
poweroff -f
