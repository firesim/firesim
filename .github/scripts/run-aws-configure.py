#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore
import os

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_env

def run_aws_configure() -> None:
    """ Runs AWS configure on the CI manager """

    with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
        run(".github/scripts/firesim-managerinit.expect {} {} {}".format(
            ci_env['AWS_ACCESS_KEY_ID'],
            ci_env['AWS_SECRET_ACCESS_KEY'],
            ci_env['AWS_DEFAULT_REGION']))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_aws_configure, hosts=["localhost"])
