#!/usr/bin/env python

import sys

from fabric.api import *

from common import manager_fsim_dir, manager_hostname
from ci_variables import ci_workflow_id

def run_scala_test(target_project, test_name):
    """ Runs a scala test under the desired target project

    target_project -- The make variable to select the desired target project makefrag

    test_name -- the full classname of the test
    """
    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim testOnly TARGET_PROJECT={} SCALA_TEST={}".format(target_project, test_name))

if __name__ == "__main__":
    execute(run_scala_test, sys.argv[1], sys.argv[2], hosts = [manager_hostname(ci_workflow_id)])
