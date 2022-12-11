#!/usr/bin/env python3

from fabric.api import prefix, settings, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def build_default_workloads():
    """ Builds workloads that will be run on F1 instances as part of CI """

    with prefix(f'cd {manager_fsim_dir} && source sourceme-f1-manager.sh'), \
         prefix(f'cd {manager_fsim_dir}/deploy/workloads'):

        # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
        with settings(warn_only=True):
            rc = run("marshal -v build br-base.json &> br-base.full.log").return_code
            if rc != 0:
                run("cat br-base.full.log")
                raise Exception("Building br-base.json failed to run")

        run("make linux-poweroff")
        run("make allpaper")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(build_default_workloads, hosts=["localhost"])
