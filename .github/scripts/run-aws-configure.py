#!/usr/bin/env python3

from fabric.api import *
import os

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_aws_configure() -> None:
    """ Runs AWS configure on the CI manager """

    with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
        run(".github/scripts/firesim-managerinit.expect {} {} {}".format(
            os.environ["AWS-ACCESS-KEY-ID"],
            os.environ["AWS-SECRET-ACCESS-KEY"],
            os.environ["AWS-DEFAULT-REGION"]))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_aws_configure, hosts=["localhost"])
