#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh
parse_ip_address

echo "Using $IP_ADDR as testing instance"

run "cd firesim/ && source sourceme-f1-manager.sh && firesim buildafi"

echo "Success"
