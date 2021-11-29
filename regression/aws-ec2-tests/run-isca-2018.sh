#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh
parse_ip_address

echo "Using $IP_ADDR as testing instance"

cd .. # firesim

run "cd firesim/ && source sourceme-f1-manager.sh && sw/firesim-software && ./marshal build -v br-base.json && ./marshal install -v br-base.json"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && make allpaper"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && ./run-all.sh"

echo "Success"
