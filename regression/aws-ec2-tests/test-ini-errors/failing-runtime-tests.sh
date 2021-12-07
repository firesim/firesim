#!/bin/bash

set -x
set -o pipefail

BASE_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
SCRIPT_DIR=$BASE_DIR/..
source $SCRIPT_DIR/defaults.sh
parse_ip_address

run_test () {
    TEST_NAME=$1
    copy_no_sym $BASE_DIR/failing-runtime-tests/$TEST_NAME $IP_ADDR:~
    OPTS="-c ~/$TEST_NAME/sample_config_runtime.ini -a ~/$TEST_NAME/sample_config_hwdb.ini"
    if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"; then
        echo "Test passed... which we don't want. FAIL!"
        run "cd firesim/ && source sourceme-f1-manager.sh && firesim terminaterunfarm -q $OPTS"
        exit 1
    fi
    run "cd firesim/ && source sourceme-f1-manager.sh && firesim terminaterunfarm -q $OPTS"
}

run_test hwdb-invalid-afi
run_test runtime-invalid-hwconfig
run_test runtime-invalid-topology
run_test runtime-invalid-workloadname

# test CLI files not existing
OPTS="-c ~/GHOST_FILE"
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"; then
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi
OPTS="-a ~/GHOST_FILE"
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"; then
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi

echo "Success"
