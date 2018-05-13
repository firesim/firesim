#!/bin/bash

#defaults TODO: Make = number of harts?
copies=1
counters=0

function usage
{
    echo "usage: intrate.sh <benchmark-name> [-H | -h | --help] [--copies <int>] [--workload <int>] [--counters]"
    echo "   benchmark-name: the spec17 run directory with binary and inputs"
    echo "   copies: number of rate instances to run (2GiB each) Default: ${copies}"
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
        --copies)
            shift;
            copies=$1
            ;;
        --counters)
            counters=1;
            ;;
        -h | -H | --help)
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
work_dir=$PWD

runscript="run.sh"
echo "Starting rate $bmark_name run with $copies copies"

echo "Creating run directories"
for i in `seq 0 $[ ${copies} - 1 ]`; do
    cp -al $work_dir/$bmark_name ${work_dir}/copy-$i
done

# In some systems we might not support for our counter program; so optionally disable it 
if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    start_counters
fi

for i in `seq 0 $[ ${copies} - 1 ]`; do
    cd $work_dir/copy-$i
   ./run.sh > ~/output/${bmark_name}_${i}.out 2> ~/output/${bmark_name}_${i}.err &
done
sleep 10
while pgrep -f run.sh > /dev/null; do sleep 10; done

if [ -z "$DISABLE_COUNTERS" -a "$counters" -ne 0 ]; then
    stop_counters
fi
