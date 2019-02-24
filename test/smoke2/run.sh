#!/bin/bash

cd root

if [[ $(tail -n 1 runOutput) != "Global : run" ]]; then
  echo "Global : run" >> runOutput 
fi
cat runOutput

sync
poweroff -f
