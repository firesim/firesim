#!/usr/bin/env python3

import argparse
from enum import Enum
from fabric.api import prefix, run, settings, execute # type: ignore

from ci_variables import ci_env

class FpgaPlatform(Enum):
    vitis = 'vitis'
    xilinx_alveo_u250 = 'xilinx_alveo_u250'

    def __str__(self):
        return self.value

parser = argparse.ArgumentParser(description='')
parser.add_argument('--platform', type=FpgaPlatform, choices=list(FpgaPlatform), required=True)
args = parser.parse_args()

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of vitis docs have been
    updated. """

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix("cd deploy"):
                run("cat config_runtime.yaml")
                if args.platform == FpgaPlatform.vitis:
                    subpath = 'AWS-EC2-F1-Getting-Started'
                elif args.platform == FpgaPlatform.xilinx_alveo_u250:
                    subpath = 'On-Premises-FPGA-Getting-Started'
                else:
                    assert False
                path = f'docs/Getting-Started-Guides/{subpath}/Running-Simulations/DOCS_EXAMPLE_config_runtime.yaml'
                run(f"cat ../{path}")
                run(f"diff config_runtime.yaml ../{path}")

if __name__ == "__main__":
    execute(run_docs_generated_components_check, hosts=["localhost"])
