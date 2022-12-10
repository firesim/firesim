#!/usr/bin/env python3

import argparse

from fabric.api import cd, prefix, settings, run, execute # type: ignore

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

    parser = argparse.ArgumentParser()
    parser.add_argument('target_project',
                        help='The make variable to select the desired target project makefrag')
    parser.add_argument('test_name',
                        help=' the full classname of the test')
    args = parser.parse_args()
    execute(run_scala_test, args.target_project, args.test_name, hosts = ["localhost"])
