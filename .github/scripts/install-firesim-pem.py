#!/usr/bin/env python3

from fabric.api import cd, local, execute # type: ignore
import os

from ci_variables import ci_env
from common import manager_home_dir, manager_fsim_pem, set_fabric_firesim_pem

def install_firesim_pem():
    """ Installs firesim.pem in the manager's home directory from the FIRESIM_PEM secret """

    with cd(manager_home_dir):
        # add firesim.pem
        with open(manager_fsim_pem, "w") as pem_file:
            pem_file.write(ci_env['FIRESIM_PEM'])
        local("chmod 600 {}".format(manager_fsim_pem))

if __name__ == "__main__":
    execute(install_firesim_pem, hosts=["localhost"])

