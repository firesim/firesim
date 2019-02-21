#!/bin/bash

#Defaults
counters=0
verify=0

function usage
{
    echo "usage: gapbs.sh <workload-name> [-H | -h | --help] [--verify] [--counters]"
    echo "    workload-name: the kernel and graph input"
    echo "    verify: if set, verifies the output of the benchmark. Default is off"
    echo "    counters: if set, runs an hpm_counters instance on each hart"
}

if [ $# -eq 0 -o "$1" == "--help" -o "$1" == "-h" ]; then
    usage
    exit 3
fi

bmark_name=$1
shift

while test $# -gt 0
do
   case "$1" in
        --counters)
            counters=1;
            ;;
        --verify)
            verify=1;
            ;;
        -h | -H | -help)
            usage
            exit
            ;;
        --*) echo "ERROR: bad option $1"
            usage
            exit 1
            ;;
        *) echo "ERROR: bad argument $1"
            usage
            exit 2
            ;;
    esac
    shift
done

mkdir -p ~/output
export OMP_NUM_THREADS=`grep -o 'hart' /proc/cpuinfo | wc -l`
echo "Starting rate $bmark_name run with $OMP_NUM_THREADS threads"

# In some systems we might not support for our counter program; so optionally disable it 
if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    start_counters
fi

if [ $verify -eq 1 ]; then
    echo "and verifying output."
    ./run/${bmark_name}.sh -v > ~/output/out 2>~/output/err
else
    ./run/${bmark_name}.sh > ~/output/out 2>~/output/err
fi

if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    stop_counters
fi
