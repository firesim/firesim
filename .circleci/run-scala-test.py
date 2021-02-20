#!/usr/bin/env python

import sys

from fabric.api import execute

from common import *
from ci_variables import *

def run_scala_test(target_project, test_name):
    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim testOnly TARGET_PROJECT={} SCALA_TEST={}".format(target_project, test_name))

if __name__ == "__main__":
    execute(run_scala_test, sys.argv[1], sys.argv[2], hosts = [manager_hostname()])
