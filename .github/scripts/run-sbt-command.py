#!/usr/bin/env python3

import argparse

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_sbt_command(target_project, command):
    """ Runs a command in SBT shell for the default project specified by the target_project makefrag

    target_project -- The make variable to select the desired target project makefrag

    command -- the command to run
    """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim sbt SBT_COMMAND={} TARGET_PROJECT={}".format(command, target_project))

if __name__ == "__main__":
    set_fabric_firesim_pem()

    parser = argparse.ArgumentParser()
    parser.add_argument('target_project',
                        help='The make variable to select the desired target project makefrag')
    parser.add_argument('command',
                        help='The command to run')
    args = parser.parse_args()
    execute(run_sbt_command, args.target_project, args.command, hosts=["localhost"])
