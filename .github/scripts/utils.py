from fabric.api import run # type: ignore
from enum import Enum
import argparse

def search_match_in_last_workloads_output_file(file_name: str = "uartlog", match_key: str = "*** PASSED ***") -> int:
    # if grep doesn't find any results, this command will fail
    out = run(f"""cd deploy/results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then grep -an "{match_key}" $LAST_DIR/*/{file_name}; fi""")
    out_split = [e for e in out.split('\n') if match_key in e]
    out_count = len(out_split)
    print(f"Found {out_count} '{match_key}' strings in {file_name}")
    return out_count

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
