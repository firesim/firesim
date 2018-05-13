#!/bin/bash

# Defaults
num_threads=1
counters=0

function usage
{
    echo "usage: intspeed.sh <benchmark-name> [-H | -h | --help] [--threads <int>] [--workload <int>]"
    echo "   benchmark-name: the spec17 run directory with binary and inputs"
    echo "   threads: number of OpenMP threads to use. Default: ${num_threads}"
    echo "   workload: which workload number to run. Leaving this unset runs all."
    echo "   counters: if set, runs an hpm_counters instance on each hart"
}

if [ $# -eq 0 -o "$1" == "--help" -o "$1" == "-h" -o "$1" == "-H" ]; then
    usage
    exit 3
fi

bmark_name=$1
shift

while test $# -gt 0
do
   case "$1" in
        --workload)
            shift;
            workload_num=$1
            ;;
        --threads)
            shift;
            num_threads=$1
            ;;
        --counters)
            counters=1;
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

work_dir=$PWD
export OMP_NUM_THREADS=$num_threads
mkdir -p ~/output

if [ -z "$workload_num" ]; then
    runscript="run.sh"
    echo "Starting speed $bmark_name run with $OMP_NUM_THREADS threads"
else
    runscript="run_workload${workload_num}.sh"
    echo "Starting speed $bmark_name (workload ${workload_num}) run with $OMP_NUM_THREADS threads"
fi

# In some systems we might not support for our counter program; so optionally disable it 
if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    start_counters
fi

# Actually start the workload
cd $work_dir/${bmark_name}
./${runscript} > ~/output/${bmark_name}_${i}.out 2> ~/output/${bmark_name}_${i}.err

if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    stop_counters
fi
