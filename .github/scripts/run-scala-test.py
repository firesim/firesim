#!/usr/bin/env python3

import sys

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_scala_test(target_project, test_name):
    """ Runs a scala test under the desired target project

    target_project -- The make variable to select the desired target project makefrag

    test_name -- the full classname of the test
    """
    with cd(manager_fsim_dir), prefix('source env.sh'):
        with settings(warn_only=True):
            rc = run("make -C sim testOnly TARGET_PROJECT={} SCALA_TEST={}".format(target_project, test_name)).return_code
            if rc != 0:
                raise Exception("Running scala test failed")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_scala_test, sys.argv[1], sys.argv[2], hosts = ["localhost"])
