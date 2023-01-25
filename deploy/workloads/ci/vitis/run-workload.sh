#!/usr/bin/env bash

trap "exit" INT
set -e
set -o pipefail

firesim launchrunfarm  -c $1 -a $2 -r $3
firesim infrasetup -c $1 -a $2 -r $3
firesim runworkload  -c $1 -a $2 -r $3
