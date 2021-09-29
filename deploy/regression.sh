#! /usr/bin/env bash

# If regenerating AGFIS, at minimum run this script to ensure the critical
# designs can boot linux successfully and power off.

# NB: The onus is still un the invoker to check the uartlogs.

echo "Diffing config_hwdb.ini against sample_config_hwdb.ini:"
diff config_hwdb.ini sample-backup-configs/sample_config_hwdb.ini
local_diff_rc=$?

echo "Diffing sample_config_hwdb.ini against origin/dev:"
git diff --exit-code origin/dev -- sample-backup-configs/sample_config_hwdb.ini
remote_diff_rc=$?

if [[ $local_diff_rc = 0 && $remote_diff_rc = 0 ]]; then
    echo "Local HWDB does not differ from origin/dev."
    echo "Did you update config_hwdb.ini with new AGFIs?"
    exit 1
fi

# Run linux-poweroff on unnetworked targets
./workloads/run-workload.sh workloads/linux-poweroff-all-no-nic.ini --withlaunch

# Run linux-poweroff on the networked target running on a f1.16xlarge
./workloads/run-workload.sh workloads/linux-poweroff-nic.ini --withlaunch
