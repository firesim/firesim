#!/usr/bin/env bash

set -e
set -o pipefail

fdir=$(pwd)
# build setup
#./build-setup.sh fast
source sourceme-f1-manager.sh

# build target software
cd ../sw/firesim-software
(cd boards/default/linux && git apply $(fdir)/sw/pdes-software/patch.linux)
./marshal -v build br-base.json

cd $(fdir)/sw/pdes-software
marshal -v build pdes-base.json
