#!/usr/bin/env bash

# This is some sugar around:
# ./firesim -c <yaml> {launchrunfarm && infrasetup && runworkload && terminaterunfarm}
# And thus will only work for workloads that do not need run other applications
# between firesim calls

# Defaults
withlaunch=0
terminate=1

function usage
{
    echo "usage: run-workload.sh <workload> [-H | -h | --help] [--noterminate] [--withlaunch]"
    echo "   workload:  the firesim-relative path to the basename of yaml files you'd like to run."
    echo "              in this case, the path will be appended with -runtime.yaml for the runtime.yaml and -runfarm.yaml for the runfarm.yaml files"
    echo "              e.x. workloads/gapbs gives"
    echo "                   workloads/gapbs-runtime.yaml and workloads/gapbs-runfarm.yaml"
    echo "   --withlaunch:  (Optional) will spin up a runfarm based on the yaml"
    echo "   --noterminate: (Optional) will not forcibly terminate runfarm instances after runworkload"
}

if [ $# -eq 0 -o "$1" == "--help" -o "$1" == "-h" -o "$1" == "-H" ]; then
    usage
    exit 3
fi

yamlbasename=$1
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

runtimeyaml=${yamlbasename}-runtime.yaml
runfarmyaml=${yamlbasename}-runfarm.yaml

trap "exit" INT
set -e
set -o pipefail

managerargs="-c $runtimeyaml -n $runfarmyaml"

if [ "$withlaunch" -ne "0" ]; then
    firesim $managerargs launchrunfarm
fi

firesim $managerargs infrasetup
firesim $managerargs runworkload

if [ "$terminate" -eq "1" ]; then
    firesim $managerargs terminaterunfarm --forceterminate
fi
