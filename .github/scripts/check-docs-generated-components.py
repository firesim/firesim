#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of docs have been
    updated. """

    with cd(manager_fsim_dir), prefix('source sourceme-f1-manager.sh'):
        with prefix("cd deploy"):
            run("cat config_runtime.yaml")
            run("cat ../docs/Running-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")
            run("diff config_runtime.yaml ../docs/Running-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")
            run("firesim --help")
            run("cat ../docs/Advanced-Usage/Manager/HELP_OUTPUT")
            run("firesim --help &> TEMP_HELP_OUTPUT")
            run("cat TEMP_HELP_OUTPUT")
            run("diff TEMP_HELP_OUTPUT ../docs/Advanced-Usage/Manager/HELP_OUTPUT")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_docs_generated_components_check, hosts=["localhost"])
