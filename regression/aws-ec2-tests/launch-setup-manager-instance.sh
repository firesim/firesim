#!/bin/bash

set -ex
set -o pipefail

if [ $# -ne 1 ]; then
    echo "$0 <FULL HASH TO TEST>"
    exit 1
fi

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $SCRIPT_DIR/defaults.sh

FULL_HASH=$1

# get the userdata file to launch manager with
rm -rf machine-launch-script.sh
wget https://raw.githubusercontent.com/firesim/firesim/$FULL_HASH/scripts/machine-launch-script.sh

# launch manager
$SCRIPT_DIR/../../deploy/awstools/awstools.py \
    launch \
    --inst_type c5.4xlarge \
    --user_data_file $PWD/machine-launch-script.sh \
    2>&1 | tee $IP_ADDR_FILE

rm -rf machine-launch-script.sh

# make sure managerinit finishes properly
run "timeout 10m grep -q \".*machine launch script complete.*\" <(tail -f machine-launchstatus)"

# setup the repo (similar to ci)

run "git clone https://github.com/firesim/firesim.git"
run "cd firesim/ && git checkout $FULL_HASH"
run "cd firesim/ && ./build-setup.sh --fast --skip-validate"
run "cd firesim/sw/firesim-software && ./init-submodules.sh"
# use local aws permissions (for now bypass the manager)
copy ~/.aws/ $IP_ADDR:~/.aws
copy ~/firesim.pem $IP_ADDR:~/firesim.pem
copy firesim-managerinit.expect $IP_ADDR:~/firesim-managerinit.expect
run "cd firesim && source sourceme-f1-manager.sh && cd ../ && ./firesim-managerinit.expect"

echo "Success"
