#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of docs have been
    updated. """

    with cd(manager_fsim_dir), prefix('source sourceme-manager.sh'):
        with prefix("cd deploy"):
            run("cat config_runtime.yaml")
            path = 'docs/Getting-Started-Guides/AWS-EC2-F1-Getting-Started/Running-Simulations/DOCS_EXAMPLE_config_runtime.yaml'
            run(f"cat ../{path}")
            run(f"diff config_runtime.yaml ../{path}")
            run("firesim --help")
            path = "docs/Advanced-Usage/Manager/HELP_OUTPUT"
            run(f"cat ../{path}")
            run("firesim --help &> TEMP_HELP_OUTPUT")
            run("cat TEMP_HELP_OUTPUT")
            run(f"diff TEMP_HELP_OUTPUT ../{path}")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_docs_generated_components_check, hosts=["localhost"])
