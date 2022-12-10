#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_sim_driver():
    """Runs compilation of F1 driver for the make-default tuple"""

    with cd(manager_fsim_dir + "/sim"), prefix('source ../env.sh'):
        run("make f1")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_sim_driver, hosts=["localhost"])
