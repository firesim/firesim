from enum import Enum
import argparse
import os

class FpgaPlatform(Enum):
    vitis = 'vitis'
    xilinx_alveo_u250 = 'xilinx_alveo_u250'

    def __str__(self):
        return self.value

def create_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('--platform', type=FpgaPlatform, choices=list(FpgaPlatform), required=True)
    args = parser.parse_args()
    return args

def setup_shell_env_vars():
    # if the following env. vars exist, then propagate to fabric subprocess
    shell_env_vars = {
        "TEST_DISABLE_VERILATOR",
        "TEST_DISABLE_VIVADO",
    }
    export_shell_env_vars = set()
    for v in shell_env_vars:
        if v in os.environ:
            export_shell_env_vars.add(f"{v}={os.environ[v]}")

    return ("export " + " ".join(export_shell_env_vars)) if export_shell_env_vars else "true"
