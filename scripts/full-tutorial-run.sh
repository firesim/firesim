#!/usr/bin/env bash

set -ex
set -o pipefail

# AMI SETUP #

################################### CHIPYARD AM
cd ~/

(
# get a subshell so can source sourceme-f1-manager.sh later

# get chipyard
git clone https://github.com/ucb-bar/chipyard -b hpca-2023-morning chipyard-morning
# chipyard init
cd chipyard-morning

# setup conda env
./build-setup.sh riscv-tools -f

source env.sh
conda install networkx

# firesim init
./scripts/firesim-setup.sh
cd sims/firesim
source sourceme-f1-manager.sh

# verilator pre-build
cd ~/chipyard-morning/sims/verilator/
make
make clean

cd ~/chipyard-morning
chmod +x scripts/repo-clean.sh
./scripts/repo-clean.sh
git checkout scripts/repo-clean.sh

)

#################################### END CHIPYARD AM PREP

#################################### CHIPYARD PM
cd ~/

(
# get a subshell so can source sourceme-f1-manager.sh later

# get chipyard
git clone https://github.com/ucb-bar/chipyard -b hpca-2023-morning chipyard-afternoon
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
unset MAKEFLAGS
make f1
export MAKEFLAGS=-j16

# build Linux target software once
cd ../sw/firesim-software
./init-submodules.sh
marshal -v build br-base.json

# sha3 stuff
cd ~/chipyard-afternoon/generators/sha3/software/
git submodule update --init esp-isa-sim
git submodule update --init linux
./build-spike.sh
./build.sh

# more sha3 workload stuff
cd ~/chipyard-afternoon/generators/sha3/software/
marshal -v build marshal-configs/sha3-linux-jtr-test.yaml
marshal -v build marshal-configs/sha3-linux-jtr-crack.yaml
marshal -v install marshal-configs/sha3*.yaml

# pre-build stuff
cd ~/chipyard-afternoon/sims/firesim/sim/
unset MAKEFLAGS
make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.QuadRocketConfig PLATFORM_CONFIG=F90MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.LargeBoomConfig PLATFORM_CONFIG=F65MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.RocketConfig PLATFORM_CONFIG=F90MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=WithNIC_DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=F65MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketConfig PLATFORM_CONFIG=F65MHz_BaseF1Config
make f1 DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLC4MB_WithDefaultFireSimBridges_WithFireSimHighPerfConfigTweaks_chipyard.Sha3RocketPrintfConfig PLATFORM_CONFIG=F30MHz_WithPrintfSynthesis_BaseF1Config
export MAKEFLAGS=-j16

cd ~/chipyard-afternoon
chmod +x scripts/repo-clean.sh
./scripts/repo-clean.sh
git checkout scripts/repo-clean.sh

)

# AMI SETUP END #

# 03 - building_custom_socs

cd ~/chipyard-morning/generators/chipyard/src/main/scala/config/
sed -i '120s/\/\/\( new sha3.*\)/\1/' TutorialConfigs.scala

cd ~/chipyard-morning/sims/verilator
make CONFIG=TutorialNoCConfig -j16
cd verilator/generated-src/chipyard*Tutorial/
ls

cd ~/chipyard-morning/tests
make -j16

cd ~/chipyard-morning/sims/verilator
make CONFIG=TutorialNoCConfig run-binary-hex BINARY=../../tests/fft.riscv

cd ~/chipyard-morning/generators/sha3/software
./build.sh

cd ~/chipyard-morning/sims/verilator
make CONFIG=TutorialNoCConfig run-binary-hex BINARY=$SHA3SW/sha3-rocc.riscv

exit 0 # TODO FINISH AFTER THIS

# 06 - building_hw_firesim

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
cat built-hwdb-entries/*

cd ~/chipyard-afternoon/generators/sha3/software/
marshal -d build marshal-configs/sha3-linux-test.yaml

# 07 - running_firesim_simulations

cd ~/chipyard-afternoon
source ./env.sh

cd $FDIR
source ./sourceme-f1-manager.sh

sed -i 's/\(- f1.2xlarge: \).*/1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(- f1.4xlarge: \).*/1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(- f1.16xlarge: \).*/1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(topology: \).*/no_net_config/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(no_net_num_nodes: \).*/1/' $FDIR/deploy/config_runtime.yaml
sed -i 's/\(default_hw_config: \).*/firesim_rocket_singlecore_no_nic_l2_lbp/' $FDIR/deploy/config_runtime.yaml

cd $FDIR/deploy
cat built-hwdb-entries/firesim_rocket_singlecore_no_nic_l2_lbp >> config_hwdb.yaml

firesim launchrunfarm
firesim infrasetup
firesim runworkload > /dev/null &

sleep 1m # wait for simulation to start

function waitForBoot {
    cd $FDIR/deploy/results-workload/
    LAST_DIR=$(ls | tail -n1)
    if [ -d "$LAST_DIR" ]; then
        while ! grep -i "Welcome to Buildroot" $LAST_DIR/*/uartlog;
        do
            echo "Waiting on boot";
            sleep 2;
        done
    else
        echo "Unable to find output directory"
        exit 111
    fi
}
export -f waitForBoot
timeout 10m bash -c waitForBoot

kill $(jobs -p) # should be noop
firesim terminaterunfarm -q
