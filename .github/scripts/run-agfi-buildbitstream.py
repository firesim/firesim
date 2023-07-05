#!/usr/bin/env python3

import sys
import os
from pathlib import Path

from fabric.api import prefix, settings, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_env

def run_agfi_buildbitstream():
    """ Runs AGFI buildbitstream"""

    relative_hwdb_path = f"deploy/sample-backup-configs/sample_config_hwdb.yaml"
    relative_build_path = f"deploy/sample-backup-configs/sample_config_build.yaml"

    with prefix(f'cd {manager_fsim_dir} && source sourceme-manager.sh'):
        rc = 0

        # unique tag based on the ci workflow and filename is needed to ensure
        # run farm is unique to each linux-poweroff test
        with prefix(f"export FIRESIM_BUILDFARM_PREFIX={ci_env['GITHUB_RUN_ID']}-{Path(__file__).stem}"):
            with settings(warn_only=True):
                # pty=False needed to avoid issues with screen -ls stalling in fabric
                build_result = run("timeout 10h firesim buildbitstream --forceterminate", pty=False)
                rc = build_result.return_code

        if rc != 0:
            log_lines = 200
            print(f"Buildbitstream failed. Printing {log_lines} of last log file:")
            run(f"""LAST_LOG=$(ls | tail -n1) && if [ -f "$LAST_LOG" ]; then tail -n{log_lines} $LAST_LOG; fi""")
            sys.exit(rc)
        else:
            # parse the output yamls, replace the sample hwdb's agfi line only
            sample_hwdb_filename = f"{manager_fsim_dir}/{relative_hwdb_path}"

            hwdb_entry_dir = f"{manager_fsim_dir}/deploy/built-hwdb-entries"
            built_hwdb_entries = [x for x in os.listdir(hwdb_entry_dir) if os.path.isfile(os.path.join(hwdb_entry_dir, x))]
            for hwdb in built_hwdb_entries:
                print(f"Printing {hwdb}")
                run(f"cat {hwdb_entry_dir}/{hwdb}")

                sample_hwdb_lines = open(sample_hwdb_filename).read().split('\n')

                with open(sample_hwdb_filename, "w") as sample_hwdb_file:
                    match_agfi = False
                    for line in sample_hwdb_lines:
                        if hwdb in line.strip().split(' ')[0].replace(':', ''):
                            # hwdb entry matches key name
                            match_agfi = True
                            sample_hwdb_file.write(line + '\n')
                        elif match_agfi == True and ("agfi:" in line.strip().split(' ')[0]):
                            # only replace this agfi
                            match_agfi = False

                            new_agfi_line = open(f"{hwdb_entry_dir}/{hwdb}").read().split("\n")[1]
                            print(f"Replacing {line.strip()} with {new_agfi_line}")

                            # print out the agfi line
                            sample_hwdb_file.write(new_agfi_line + '\n')
                        else:
                            # if no match print other lines
                            sample_hwdb_file.write(line + '\n')

                    if match_agfi == True:
                        sys.exit("::ERROR:: Unable to find matching AGFI key for HWDB entry")

            # strip newlines from end of file
            with open(sample_hwdb_filename, "r+") as sample_hwdb_file:
                content = sample_hwdb_file.read()
                content = content.rstrip('\n')
                sample_hwdb_file.seek(0)

                sample_hwdb_file.write(content)
                sample_hwdb_file.truncate()

            print(f"Printing {sample_hwdb_filename}...")
            run(f"cat {sample_hwdb_filename}")

            # share agfis
            sample_build_filename = f"{manager_fsim_dir}/{relative_build_path}"
            sample_build_lines = open(sample_build_filename).read().split('\n')
            with open(sample_build_filename, "w") as sample_build_file:
                for line in sample_build_lines:
                    if "somebodysname:" in line:
                        sample_build_file.write("#" + line + "\n")
                    elif "public:" in line:
                        # get rid of the comment
                        sample_build_file.write(line.replace('#', '') + "\n")
                    else:
                        sample_build_file.write(line + "\n")

            print(f"Printing {sample_build_filename}...")
            run(f"cat {sample_build_filename}")

            run(f"firesim shareagfi -a {sample_hwdb_filename} -b {sample_build_filename}")

            # copy back to workspace area so you can PR it
            run(f"cp -f {sample_hwdb_filename} {ci_env['GITHUB_WORKSPACE']}/{relative_hwdb_path}")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_agfi_buildbitstream, hosts=["localhost"])
