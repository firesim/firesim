#!/bin/bash

cd root

if [[ $(tail -n 1 j1_output) != "j1 : run" ]]; then
  echo "j1 : run" >> j1_output
fi
cat runOutput
cat j1_output

sync
poweroff -f
