#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_scalafmt_check():
    """Runs scalafmtCheckAll on FireSim subprojects."""

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim scala-lint-check")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_scalafmt_check, hosts=["localhost"])
