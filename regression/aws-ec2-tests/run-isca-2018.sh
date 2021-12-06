#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh

cd .. # firesim

run "cd firesim/ && source sourceme-f1-manager.sh && sw/firesim-software && ./marshal -v build br-base.json && ./marshal -v install br-base.json"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && make allpaper"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && ./run-all.sh"

echo "Success"
