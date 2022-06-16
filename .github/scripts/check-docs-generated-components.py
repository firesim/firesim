#!/usr/bin/env python3

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of docs have been
    updated. """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("cd deploy && cat config_runtime.yaml")
        run("cd deploy && cat ../docs/Running-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")
        run("cd deploy && diff config_runtime.yaml ../docs/Running-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")


        run("cd deploy && firesim --help")
        run("cd deploy && cat ../docs/Advanced-Usage/Manager/HELP_OUTPUT")
        run("cd deploy && firesim --help &> TEMP_HELP_OUTPUT")
        run("cd deploy && cat TEMP_HELP_OUTPUT")
        run("cd deploy && diff TEMP_HELP_OUTPUT ../docs/Advanced-Usage/Manager/HELP_OUTPUT")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_docs_generated_components_check, hosts=["localhost"])
