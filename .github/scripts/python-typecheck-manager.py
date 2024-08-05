#!/usr/bin/env python3

from fabric.api import prefix, run, execute # type: ignore

import fabric_cfg
from ci_variables import ci_env

def run_typecheck():
    """Runs manager python typecheck."""

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            run("./scripts/run-manager-python-typecheck.sh")

if __name__ == "__main__":
    execute(run_typecheck, hosts=["localhost"])
