#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh

run "cd firesim/ && source sourceme-f1-manager.sh && cd sw/firesim-software && ./marshal -v build br-base.json && ./marshal -v install br-base.json"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && make linux-poweroff"
run "cd firesim/ && source sourceme-f1-manager.sh && ./deploy/workloads/run-workload.sh workloads/linux-poweroff-all-no-nic.ini --withlaunch"
run "cd firesim/ && source sourceme-f1-manager.sh && ./deploy/workloads/run-workload.sh workloads/linux-poweroff-nic.ini --withlaunch"

echo "Success"
