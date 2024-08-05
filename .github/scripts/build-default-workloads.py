#!/usr/bin/env python3

from fabric.api import prefix, settings, run, execute # type: ignore

import fabric_cfg
from ci_variables import ci_env

def build_default_workloads():
    """Builds default workloads packaged with FireSim"""

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            run("marshal -v build br-base.json")
            with prefix("cd deploy/workloads"):
                run("make linux-poweroff")
                run("make allpaper")

if __name__ == "__main__":
    execute(build_default_workloads, hosts=["localhost"])
