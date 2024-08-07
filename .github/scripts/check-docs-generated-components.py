#!/usr/bin/env python3

from fabric.api import prefix, run, settings, execute # type: ignore

import fabric_cfg
from ci_variables import ci_env
from utils import create_args, FpgaPlatform

args = create_args()

def run_docs_generated_components_check():
    """Ensure generated components of docs have been updated."""

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
                    raise Exception(f"Unable to run this script with {args.platform}")
                path = f'docs/Getting-Started-Guides/{subpath}/Running-Simulations/DOCS_EXAMPLE_config_runtime.yaml'
                run(f"cat ../{path}")
                run(f"diff config_runtime.yaml ../{path}")

if __name__ == "__main__":
    execute(run_docs_generated_components_check, hosts=["localhost"])
