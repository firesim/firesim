#!/usr/bin/env python3

import sys

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_ref_name

def run_default_buildafi():
    """ Runs buildafi """

    with prefix('cd {} && source sourceme-f1-manager.sh'.format(manager_fsim_dir)):
        rc = 0
        with settings(warn_only=True):
            # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
            # pty=False needed to avoid issues with screen -ls stalling in fabric
            rc = run("timeout 16h firesim buildafi --forceterminate &> buildafi.log", pty=False).return_code
        if rc != 0:
            print("Buildafi failed. Printing last lines of log. See buildafi.log for full info")
            print("Log start =================================================================")
            run("tail -n 300 buildafi.log")
            print("Log end ===================================================================")
            sys.exit(rc)
        else:
            # parse the output yamls, replace the sample hwdb's agfi line
            hwdb_entry_dir = "{}/deploy/built-hwdb-entries".format(manager_fsim_dir)
            built_hwdb_entries = [x for x in os.listdir(hwdb_entry_dir) if os.path.isfile(os.path.join(hwdb_entry_dir, x))]

            sample_hwdb_postfix = "deploy/sample-backup-configs/sample_config_hwdb.ini"
            sample_hwdb_filename = "{}/{}".format(manager_fsim_dir, sample_hwdb_postfix)
            for hwdb in built_hwdb_entries:
                sample_hwdb_lines = open(sample_hwdb_filename).read().split('\n')

                with open(sample_hwdb_filename, "w") as sample_hwdb_file:
                    match_agfi = False
                    for line in sample_hwdb_lines:
                        # found the hwdb entry
                        if hwdb in line:
                            match_agfi = True
                            sample_hwdb_file.write(line + '\n')
                        elif match_agfi == True and "agfi=" in line:
                            # only replace this agfi
                            match_agfi = False
                            # print out the agfi line
                            sample_hwdb_file.write(open("{}/{}".format(hwdb_entry_dir, hwdb)).read().split("\n")[1] + '\n')
                        else:
                            sample_hwdb_file.write(line + '\n')

                    if match_agfi == True:
                        sys.exit("ERROR: Unable to find matching AGFI line for HWDB entry")

            # share agfis
            sample_build_filename = "{}/deploy/sample-backup-configs/sample_config_build.ini".format(manager_fsim_dir)
            sample_build_lines = open(sample_build_filename).read().split('\n')
            with open(sample_build_filename, "w") as sample_build_file:
                for line in sample_build_lines:
                    if "somebodysname=" in line:
                        sample_build_file.write("#" + line + "\n")
                    elif "public=" in line:
                        # get rid of the comment
                        sample_build_file.write(line[1:] + "\n")
                    else:
                        sample_build_file.write(line + "\n")

            run("firesim shareagfi -a {} -b {}".format(sample_hwdb_filename, sample_build_filename))

            # make PR
            # TODO: probably more efficient way? - https://github.com/marketplace/actions/add-commit
            run("git clone --depth 1 {} temp-firesim".format(manager_fsim_dir))
            with prefix("cd temp-firesim"):
                run("git checkout {}".format(ci_ref_name))
                run("cp {} {}".format(sample_hwdb_filename, sample_hwdb_postfix))
                run("git commit -am \"Update HWDB\"")
                run("git push origin {}".format(ci_ref_name))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_default_buildafi, hosts=["localhost"])
