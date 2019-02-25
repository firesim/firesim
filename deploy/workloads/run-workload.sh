#!/usr/bin/env bash

# This is some sugar around:
# ./firesim -c <ini> {launchrunfarm && infrasetup && runworkload && terminaterunfarm}
# And thus will only work for workloads that do not need run other applications
# between firesim calls

# Defaults
withlaunch=0
terminate=1

function usage
{
    echo "usage: run-workload.sh <workload.ini> [-H | -h | --help] [--noterminate] [--withlaunch]"
    echo "   workload.ini:  the firesim-relative path to the workload you'd like to run"
    echo "                  e.g. workloads/gapbs.ini"
    echo "   --withlaunch:  (Optional) will spin up a runfarm based on the ini"
    echo "   --noterminate: (Optional) will not forcibly terminate runfarm instances after runworkload"
}

if [ $# -eq 0 -o "$1" == "--help" -o "$1" == "-h" -o "$1" == "-H" ]; then
    usage
    exit 3
fi

ini=$1
shift

while test $# -gt 0
do
   case "$1" in
        --withlaunch)
            withlaunch=1
            ;;
        --noterminate)
            terminate=0;
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

trap "exit" INT
set -e
set -o pipefail

if [ "$withlaunch" -ne "0" ]; then
    firesim -c $ini launchrunfarm
fi

firesim -c $ini infrasetup
firesim -c $ini runworkload

if [ "$terminate" -eq "1" ]; then
    firesim -c $ini terminaterunfarm --forceterminate
fi
