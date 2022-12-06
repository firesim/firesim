#!/usr/bin/env python3

from fabric.api import *
import os

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_aws_configure() -> None:
    """ Runs AWS configure on the CI manager """

    with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
        run(".github/scripts/firesim-managerinit.expect {} {} {}".format(
            os.environ["AWS_ACCESS_KEY_ID"],
            os.environ["AWS_SECRET_ACCESS_KEY"],
            os.environ["AWS_DEFAULT_REGION"]))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_aws_configure, hosts=["localhost"])
