#!/usr/bin/env bash

set -ex
set -o pipefail

# AMI SETUP START #

export MAKEFLAGS=-j16
CY_COMMIT=9b3fef

################################### CHIPYARD AM
cd ~/

(
# get a subshell so can source sourceme-f1-manager.sh later

# get chipyard
git clone https://github.com/ucb-bar/chipyard chipyard-morning
cd chipyard-morning
git checkout $CY_COMMIT
cd ~

# chipyard init
cd chipyard-morning

# setup conda env
./build-setup.sh riscv-tools -f

source env.sh

# firesim init
./scripts/firesim-setup.sh
cd sims/firesim
source sourceme-f1-manager.sh

# verilator pre-build
cd $MCYDIR/sims/verilator/
make
make clean

cd $MCYDIR
./scripts/repo-clean.sh

)

#################################### END CHIPYARD AM PREP

#################################### CHIPYARD PM
cd ~/

(
# get a subshell so can source sourceme-f1-manager.sh later

# get chipyard
git clone https://github.com/ucb-bar/chipyard chipyard-afternoon
cd chipyard-morning
git checkout $CY_COMMIT
cd ~

# chipyard init
cd chipyard-afternoon

# setup conda env
./build-setup.sh riscv-tools -f

source env.sh

# firesim init
./scripts/firesim-setup.sh
cd sims/firesim
source sourceme-f1-manager.sh

# run through elaboration flow once to get chisel/sbt all setup
cd sim
make f1

# build Linux target software once
cd ../sw/firesim-software
./init-submodules.sh
marshal -v build br-base.json

# pre-build stuff
cd ~/chipyard-afternoon/sims/firesim/sim/
make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.QuadRocketConfig PLATFORM_CONFIG=F90MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.LargeBoomConfig PLATFORM_CONFIG=F65MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.RocketConfig PLATFORM_CONFIG=F90MHz_BaseF1Config

cd ~/chipyard-afternoon
./scripts/repo-clean.sh

)

#################################### END CHIPYARD AM PREP

# AMI SETUP END #

MCYDIR="~/chipyard-morning"
ACYDIR="~/chipyard-afternoon"

cd $ACYDIR
source ./env.sh

cd $FDIR
source ./sourceme-f1-manager.sh

exit 0 # TODO FINISH AFTER THIS

# 03 - building_custom_socs

cd $MCYDIR/sims/verilator
make CONFIG=TutorialLeanGemminiConfig -j16
cd generated-src/chipyard*Tutorial/
ls
cd gen-collateral/
ls

cd $MCYDIR/tests
make -j16
spike hello.riscv
timeout 10m spike mt-hello.riscv || true
timeout 10m spike -p4 mt-hello.riscv || true

# TODO: add
#spike mt-gemmini.riscv
#spike --extension=gemmini mt-gemmini.riscv
#spike --extension=gemmini -p4 mt-gemmini.riscv

cd $MCYDIR/sims/verilator
make CONFIG=TutorialLeanGemminiConfig BINARY=$MCYDIR/tests/hello.riscv run-binary-hex
timeout 10m make CONFIG=TutorialLeanGemminiConfig BINARY=$MCYDIR/tests/mt-hello.riscv run-binary-hex || true
# TODO: add
#make CONFIG=TutorialLeanGemminiConfig BINARY=$MCYDIR/tests/mt-gemmini.riscv run-binary-hex

cd $MCYDIR/sims/verilator
make CONFIG=TutorialManyCoreNoCConfig verilog

# TODO: when xcelium support added, run TutorialManyCoreNoCConfig with mt-hello.riscv

make find-config-fragments

# 07 - building_hw_firesim

FDIR="~/chipyard-afternoon/sims/firesim"

cd $FDIR
ls

cd $FDIR/sim
make DESIGN=FireSim

cd $FDIR/sim/generated-src/f1
ls
ls *

cd $FDIR/deploy
firesim managerinit --platform f1
ls

cd $FDIR/deploy

# 08 - building_sw_firesim


cd $ACYDIR/software/tutorial
ls

cd marshal-configs
marshal -v -d build resnet50-linux.yaml
marshal -d launch -s resnet50-linux.yaml

marshal -v -d build mobilenet-baremetal.yaml
marshal -v -d install mobilenet-baremetal.yaml

cd $FDIR/deploy/workloads
cat mobilenet-baremetal.json

# 09 - running_firesim_simulations

sed -i 's/\(- f1.2xlarge: \).*/\1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(topology: \).*/\1no_net_config/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(no_net_num_nodes: \).*/\1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(default_hw_config: \).*/\1firesim_rocket_singlecore_no_nic_l2_lbp/' $FDIR/deploy/config_runtime.yaml

# TODO: running the poweroff so simulation finishes
make linux-poweroff
sed -i 's/\(workload: \).*/\1linux-poweroff-uniform.json/' $FDIR/deploy/config_runtime.yaml

# DEBUG
cat $FDIR/deploy/config_runtime.yaml

cd $FDIR/deploy
cat built-hwdb-entries/firesim_rocket_singlecore_no_nic_l2_lbp >> config_hwdb.yaml

# DEBUG
TIMEOUTPREFIX="timeout 1h"

$TIMEOUTPREFIX firesim launchrunfarm
$TIMEOUTPREFIX firesim infrasetup
$TIMEOUTPREFIX firesim runworkload

# DEBUG
cd results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n300 $LAST_DIR/*/*; fi

cd $FDIR/deploy
sed -i 's/\(default_hw_config: \).*/\1firesim_gemmini_rocket_singlecore_no_nic/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(workload: \).*/\1mobilenet-baremetal.json/' $FDIR/deploy/config_runtime.yaml
$TIMEOUTPREFIX firesim infrasetup
$TIMEOUTPREFIX firesim runworkload

# DEBUG
cd results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n300 $LAST_DIR/*/*; fi

# 10 - instrumenting_debugging_firesim

sed -i 's/\(uartlog",\).*/\1 "synthesized-prints.out*"/' $FDIR/deploy/workloads/mobilenet-baremetal.json
$TIMEOUTPREFIX firesim infrasetup
$TIMEOUTPREFIX firesim runworkload

$TIMEOUTPREFIX firesim terminaterunfarm -q
