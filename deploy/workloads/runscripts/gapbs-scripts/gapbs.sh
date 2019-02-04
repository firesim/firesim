#!/bin/bash
verify=0
function usage
{
    echo "usage: gapbs.sh <workload-name> [-H | -h | --help] [--verify]"
    echo "    workload-name: the kernel and graph input"
    echo "    verify: if set, verifies the output of the benchmark. Default is off"
}

if [ $# -eq 0 -o "$1" == "--help" -o "$1" == "-h" ]; then
    usage
    exit 3
fi

bmark_name=$1
shift
mkdir -p ~/output
export OMP_NUM_THREADS=`grep -o 'hart' /proc/cpuinfo | wc -l`
echo "Starting rate $bmark_name run with $OMP_NUM_THREADS threads"
if [ "$1" == "--verify" ]; then
    echo "and verifying output."
    ./run/${bmark_name}.sh -v > ~/output/out 2>~/output/err
else
    ./run/${bmark_name}.sh > ~/output/out 2>~/output/err
fi
