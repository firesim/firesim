#!/usr/bin/env python3

from fabric.api import *
import os

from ci_variables import ci_aws_access_key_id, ci_aws_secret_access_key, ci_aws_default_region
from common import manager_fsim_dir, set_fabric_firesim_pem

def run_managerinit() -> None:
    """ Runs AWS configure on the CI manager """

    with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
        run(".github/scripts/firesim-managerinit.expect {} {} {}".format(
            ci_aws_access_key_id,
            ci_aws_secret_access_key,
            ci_aws_default_region))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_managerinit, hosts=["localhost"])
