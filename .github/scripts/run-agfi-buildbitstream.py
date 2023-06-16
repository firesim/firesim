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

        if True:
            # parse the output yamls, replace the sample hwdb's agfi line only
            sample_hwdb_filename = f"{manager_fsim_dir}/{relative_hwdb_path}"

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
