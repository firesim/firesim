#!/usr/bin/env bash

# This script runs a RISC-V assembly test in RTL simulation at the three
# supported abstraction levels and captures the necessary portions of the log
# to calculate simulation rates
#
# Abstraction levels:
# Target -> Just the target RTL
# MIDAS  -> The target post-transformations, fpga-hosted models & widgets
# FPGA   -> The whole RTL design pre-synthesis
#
# This requires a VCS license.
# Berkeley users: If running on millenium machines, source scripts/setup_vcsmx_env.sh

# The ISA test to run
TEST=rv64ui-v-add
#TEST=rv64ui-p-simple

# The file into which we dump all the relevant pieces of simulation log. Some
# post-processing is still required.
REPORT_FILE=$(pwd)/runtime.rpt

MAKE_THREADS=4

cd $(dirname $0)/..
firesim_root=$(pwd)
test_path=$RISCV/riscv64-unknown-elf/share/riscv-tests/isa/$TEST

echo -e "FireSim RTL Simulation Execution Rates\n" > $REPORT_FILE
################################################################################
# TARGET level
################################################################################
export DESIGN=FireSimNoNIC
export TARGET_CONFIG=FireSimRocketChipConfig
export PLATFORM_CONFIG=FireSimConfig
export SIM_ARGS=+verbose
export TIME="%C %E real, %U user, %S sys"

for optlevel in 0 1 2
do

    echo -e "\nVerilator TARGET-level Simulation, -O${optlevel}\n" >> $REPORT_FILE
    ## Verilator
    cd $firesim_root/target-design/firechip/verisim
    sim=simulator-example-DefaultExampleConfig

    # Hack...
    sed -i "s/-O[0-3]/-O${optlevel}/" Makefile
    make clean
    /usr/bin/time -a -o $REPORT_FILE make
    /usr/bin/time -a -o $REPORT_FILE make debug


    echo -e "\nNo Waves\n" >> $REPORT_FILE
    /usr/bin/time -a -o $REPORT_FILE ./$sim $SIM_ARGS $test_path &> nowaves.log
    tail nowaves.log >> $REPORT_FILE
    /usr/bin/time -a -o $REPORT_FILE ./$sim-debug $SIM_ARGS -vtest.vcd $test_path &> waves.log
    echo -e "\nWaves Enabled\n" >> $REPORT_FILE
    tail waves.log >> $REPORT_FILE
done

echo -e "\nTarget-level VCS\n" >> $REPORT_FILE
cd $firesim_root/target-design/firechip/vsim/
sim=simv-example-DefaultExampleConfig
/usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS
/usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS debug

echo -e "\nNo Waves\n" >> $REPORT_FILE
/usr/bin/time -a -o $REPORT_FILE ./$sim $SIM_ARGS $test_path &> nowaves.log
tail nowaves.log >> $REPORT_FILE
echo -e "\nWaves Enabled\n" >> $REPORT_FILE
/usr/bin/time -a -o $REPORT_FILE ./$sim-debug $SIM_ARGS $test_path &> waves.log
tail waves.log >> $REPORT_FILE

################################################################################
## MIDAS level
################################################################################
ml_output_dir=$firesim_root/sim/output/f1/$DESIGN-$TARGET_CONFIG-$PLATFORM_CONFIG
test_symlink=$ml_output_dir/$TEST

for optlevel in 0 1 2
do
    echo -e "\nMIDAS-level Simulation, -O${optlevel}\n" >> $REPORT_FILE
    cd $firesim_root/sim
    make clean
    make -j$MAKE_THREADS
    /usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS VERILATOR_CXXOPTS=-O${optlevel} verilator
    /usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS VERILATOR_CXXOPTS=-O${optlevel} verilator-debug
    /usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS VCS_CXXOPTS=-O${optlevel} vcs
    /usr/bin/time -a -o $REPORT_FILE make -j$MAKE_THREADS VCS_CXXOPTS=-O${optlevel} vcs-debug
    mkdir -p $ml_output_dir

    # Symlink it twice so we have unique targets for vcs and verilator
    ln -sf $test_path $ml_output_dir/$TEST
    ln -sf $test_path $ml_output_dir/$TEST-vcs

    echo -e "\nWaves Off, -O${optlevel}\n" >> $REPORT_FILE
    make EMUL=vcs ${test_symlink}-vcs.out
    make ${test_symlink}.out
    grep -Eo "simulation speed = .*" $ml_output_dir/*out >> $REPORT_FILE

    echo -e "\nWaves On, -O${optlevel}\n" >> $REPORT_FILE
    make EMUL=vcs ${test_symlink}-vcs.vpd
    make ${test_symlink}.vpd
    grep -Eo "simulation speed = .*" $ml_output_dir/*out >> $REPORT_FILE
done

################################################################################
# FPGA level
################################################################################
# Unlike the other levels, the driver and dut communicate through pipes

cd $firesim_root/sim
echo -e "\nFPGA-level XSIM - Waves On\n" >> $REPORT_FILE
make xsim
make xsim-dut | tee dut.out &
# Wait for the dut to come up; Compilation time is long.
while [[ $(grep driver_to_xsim dut.out) == '' ]]; do sleep 1; done
make run-xsim SIM_BINARY=$test_path &> driver.out
# These are too slow for the reported simulation rate to be non-zero; so tail
tail driver.out >> $REPORT_FILE

echo -e "\nFPGA-level VCS - Waves On\n" >> $REPORT_FILE
make xsim
make xsim-dut VCS=1 | tee vcs-dut.out &
# Wait for the dut to come up; Compilation time is long.
while [[ $(grep driver_to_xsim vcs-dut.out) == '' ]]; do sleep 1; done
make run-xsim SIM_BINARY=$test_path &> vcs-driver.out
# These are too slow for the reported simulation rate to be non-zero; so tail
tail vcs-driver.out >> $REPORT_FILE
