#!/bin/bash

set -e

RISCV=$1
PATH=$2
LD_LIBRARY_PATH=$3
FIRESIM_DIR=$4
MAKE_COMMAND=$5

cd $FIRESIM_DIR

export RISCV=$RISCV
export PATH=$PATH
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH

source sourceme-f1-manager.sh

# Enter simulation dir
cd sim

$MAKE_COMMAND
