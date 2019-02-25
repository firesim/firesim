#!/bin/bash
echo "Global : init" >> /root/testFile
cat /root/testFile

sync
poweroff -f
