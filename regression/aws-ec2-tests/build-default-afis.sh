#!/bin/bash

set -ex
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh

run "cd firesim/ && source sourceme-f1-manager.sh && firesim buildbitstream"

echo "Success"
