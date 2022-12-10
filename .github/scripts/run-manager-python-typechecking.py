#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_typecheck():
    """Runs manager python typecheck."""

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("./scripts/run-manager-python-typecheck.sh")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_typecheck, hosts=["localhost"])
