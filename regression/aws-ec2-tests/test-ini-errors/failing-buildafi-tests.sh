#!/bin/bash

set -x
set -o pipefail

BASE_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
SCRIPT_DIR=$BASE_DIR/..
source $SCRIPT_DIR/defaults.sh
parse_ip_address

run_test () {
    TEST_NAME=$1
    copy_no_sym $BASE_DIR/failing-buildafi-files/$TEST_NAME $IP_ADDR:~
    if run "cd firesim/ && source sourceme-f1-manager.sh && firesim buildafi -b ~/$TEST_NAME/sample_config_build.ini -r ~/$TEST_NAME/sample_config_build_recipes.ini"; then
        echo "Test passed... which we don't want. FAIL!"
        exit 1
    fi
}

run_test invalid-build-section
run_test invalid-recipe-inst-type

# test CLI files not existing
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim buildafi -b ~/GHOST_FILE"; then
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim buildafi -r ~/GHOST_FILE"; then
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi

echo "Success"
