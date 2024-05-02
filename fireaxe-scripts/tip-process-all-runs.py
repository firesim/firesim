#!/usr/bin/env python3


import os
import json
import argparse
import sys
import pandas as pd

parser = argparse.ArgumentParser(description="Arguments for processing TIP outputs")
parser.add_argument("--fpgas-per-partition", type=int, required=True, help="FPGAs per partition")
parser.add_argument("--firesim-sim-dir", type=str, required=True, help="FIRESIM_SIMULATION_DIR")
parser.add_argument("--output-dir", type=str, required=True, help="Directory for output files")
parser.add_argument("--cfg-pfx", type=str, required=True, help="CONFIG_PFX")
parser.add_argument("--benchmark-name", type=str, required=True, help="BENCHMARK_NAME")
parser.add_argument("--intermediate-dir", type=str, required=True, help="INTERMEDIATE_DIRECTORY")
args = parser.parse_args()

def bash(cmd):
    fail = os.system(cmd)
    if fail:
        print(f'[*] failed to execute {cmd}')
        sys.exit(1)

def riscv_objdump_bare(riscv_bin, riscv_objdump):
    bash(f"riscv64-unknown-elf-objdump -D {riscv_bin} > {riscv_objdump}")

def tip_process(sim_slot_dir, riscv_objdump, out_file):
    print(f"{sim_slot_dir} {riscv_objdump} {out_file}")
    bash(f"./tip-process.py --tip-output {sim_slot_dir}/ORACLE-OUT --tip-objdump {riscv_objdump} > {out_file}")

def process_sim_slot(sim_slot_dir, binary, output_file):
    riscv_objdump = os.path.join(sim_slot_dir, "OBJDUMP")
    riscv_bin     = os.path.join(sim_slot_dir, binary)
    riscv_objdump_bare(riscv_bin, riscv_objdump)
    tip_process(sim_slot_dir, riscv_objdump, output_file)

def get_binary_names(workload_spec):
    if 'common_bootbinary' in workload_spec.keys():
        return [f"workload_spec['benchmark_name']-workload_spec['common_bootbinary']"]
    else:
        workloads = workload_spec['workloads']
        ret = []
        for workload in workloads:
            ret.append(f"{workload['name']}-{workload['bootbinary']}")
        return ret

# Format
# Cycles: <num cycles> Insts: <num insts>
def collect_ipc(sim_dir):
    with open(os.path.join(sim_dir, "uartlog"), "r") as f:
        lines = f.readlines()
        for line in lines:
            words = line.split()
            if (len(words) == 4) and (words[0] == "Cycles:") and (words[2] == "Insts:"):
                cycles = int(words[1])
                insts  = int(words[3])
                ipc = float(insts / cycles)
                return ipc
        assert(False, "Could not find IPC from uartlog")

def process_all_sim_slots(benchmark_name):
    workload_spec_json = open(f"tip-intermediate/{benchmark_name}-firesim-workload-specs.json", "r")
    workload_specs = json.load(workload_spec_json)
    workload_spec_json.close()

    ipc = {}
    for workload_name in workload_specs:
        workload_json = f"../deploy/workloads/{workload_name}.json"
        f = open(workload_json, "r")
        workload_spec = json.load(f)
        f.close()

        binary_names = get_binary_names(workload_spec)
        for sim_slot_idx in range(0, len(binary_names), args.fpgas_per_partition):
            binary_name = binary_names[sim_slot_idx]
            sim_dir = os.path.join(args.firesim_sim_dir, f"{args.cfg_pfx}-{workload_name}", f"sim_slot_{sim_slot_idx}")
            output_file = os.path.join(args.output_dir, binary_name)
            process_sim_slot(sim_dir, binary_name, output_file)
            ipc[binary_name] = collect_ipc(sim_dir)

    df = pd.DataFrame.from_dict(ipc, orient='index')
    print(df)
    print(ipc)
    df.to_csv(os.path.join(args.intermediate_dir, f"TIP-IPC-{args.cfg_pfx}-{benchmark_name}.csv"))

def main():
    process_all_sim_slots(args.benchmark_name)

if __name__=="__main__":
    main()
