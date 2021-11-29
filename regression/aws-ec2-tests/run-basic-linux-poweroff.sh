
use firemarshal test/clean.yaml

#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh
parse_ip_address

echo "Using $IP_ADDR as testing instance"

cd .. # firesim

run "cd firesim/ && source sourceme-f1-manager.sh && sw/firesim-software && ./marshal build -v br-base.json && ./marshal install -v br-base.json"
run "cd firesim/ && source sourceme-f1-manager.sh && cd deploy/workloads/ && make linux-poweroff"
run "cd firesim/ && source sourceme-f1-manager.sh && ./workloads/run-workload.sh workloads/linux-poweroff-all-no-nic.ini --withlaunch"
run "cd firesim/ && source sourceme-f1-manager.sh && ./workloads/run-workload.sh workloads/linux-poweroff-nic.ini --withlaunch"

echo "Success"
