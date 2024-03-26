#!/usr/bin/env python3

import argparse
from enum import Enum
from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

class FpgaPlatform(Enum):
    vitis = 'vitis'
    f1 = 'f1'
    xilinx_alveo_u250 = 'xilinx_alveo_u250'

    def __str__(self):
        return self.value

parser = argparse.ArgumentParser(description='')
parser.add_argument('--platform', type=FpgaPlatform, choices=list(FpgaPlatform), required=True)
args = parser.parse_args()

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of docs have been
    updated. """

    with cd(manager_fsim_dir), prefix('source sourceme-manager.sh'):
        with prefix("cd deploy"):
            run("cat config_runtime.yaml")
            if args.platform == FpgaPlatform.f1:
                subpath = 'AWS-EC2-F1-Getting-Started'
            elif args.platform == FpgaPlatform.xilinx_alveo_u250 or args.platform == FpgaPlatform.f1:
                subpath = 'On-Premises-FPGA-Getting-Started'
            else:
                assert False
            path = f'docs/Getting-Started-Guides/{subpath}/Running-Simulations/DOCS_EXAMPLE_config_runtime.yaml'
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
