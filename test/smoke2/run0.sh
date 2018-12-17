#!/bin/bash

cd root

if [[ $(tail -n 1 j0_output) != "j0 : run" ]]; then
  echo "j0 : run" >> j0_output
fi
cat runOutput
cat j0_output

sync
poweroff -f
