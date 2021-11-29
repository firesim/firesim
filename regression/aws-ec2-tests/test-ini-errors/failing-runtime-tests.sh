# test hwdb issues - invalid afi, deploy triplet, and customruntime config
# runtime
 - no runfarm tag
 - invalid topology
 - what happens when there is no_net_num_nodes + a net topo
 - invalid hwconfig
 - invalid workloadname
 - invalid terminate on completion:q:tabe

#!/bin/bash

set -x
set -o pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/../defaults.sh
parse_ip_address

echo "Using $IP_ADDR as testing instance"

run_test () {
    TEST_NAME=$1
    copy $SCRIPT_DIR/failing-runtime-tests/$TEST_NAME $IP_ADDR:~
    OPTS="-c ~/$TEST_NAME/sample_config_runtime.ini -a ~/$TEST_NAME/sample_config_hwdb.ini"
    if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"; then
        echo "Test passed... which we don't want. FAIL!"
        run "cd firesim/ && source sourceme-f1-manager.sh && firesim terminaterunfarm -q $OPTS"
        exit 1
    fi
    run "cd firesim/ && source sourceme-f1-manager.sh && firesim terminaterunfarm -q $OPTS"
}

run_test hwdb-invalid-afi
run_test hwdb-invalid-deptriplet
run_test hwdb-invalid-runtime-cfg
run_test runtime-invalid-hwconfig
run_test runtime-invalid-terminate-on-complete
run_test runtime-invalid-topology
run_test runtime-invalid-workloadname
run_test runtime-no-runfarm-tag

# test CLI files not existing
OPTS="-c ~/GHOST_FILE"
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"; then
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi
OPTS="-a ~/GHOST_FILE"
if run "cd firesim/ && source sourceme-f1-manager.sh && firesim launchrunfarm $OPTS && firesim infrasetup $OPTS"
    echo "Test passed... which we don't want. FAIL!"
    exit 1
fi

echo "Success"
