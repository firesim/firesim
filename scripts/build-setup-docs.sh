#!/usr/bin/env bash

# A minimal build-setup script to pull in all of the scala sources required to
# build scala doc.
set -e
set -o pipefail

RDIR=$(pwd)
git submodule update --init target-design/chipyard
cd $RDIR/target-design/chipyard
./scripts/init-submodules-no-riscv-tools.sh --no-firesim
cd $RDIR
echo "export FIRESIM_ENV_SOURCED=1" > scala-doc-env.sh
echo "export FIRESIM_STANDALONE=1" >> scala-doc-env.sh
