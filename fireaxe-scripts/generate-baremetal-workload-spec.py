#!/usr/bin/env python3

import argparse
import os
import json

parser = argparse.ArgumentParser(description="Arguments for generating workload specifications")
parser.add_argument("--parallel-sims", type=int, default=1, help="Number of parallel simulations that can be run")
parser.add_argument("--binary-dir", type=str, required=True, help="deploy/workload/<directory-name>")
parser.add_argument("--num-fpgas-per-partition", type=int, required=True, help="Number of FPGAs used by a single partition sim")
args = parser.parse_args()


base_dir = '../deploy/workloads'
binary_dir = os.path.join(base_dir, args.binary_dir)
files = [f for f in os.listdir(binary_dir) if os.path.isfile(os.path.join(binary_dir, f))]
if '.PLACEHOLDER' in files:
    files.remove('.PLACEHOLDER')

group_size = args.parallel_sims
num_sims = int((len(files) + group_size - 1) / group_size)
left = len(files)

workloads_list = []
for sim_group in range(num_sims):
    workload = {}
    workload['benchmark_name'] = args.binary_dir
    workload['common_simulation_outputs'] = ['uartlog']
    workload['common_rootfs'] = None
    workload['workloads'] = []
    for sim_id in range(min(left, group_size)):
        for slot_offset in range(args.num_fpgas_per_partition):
            job = {}
            binary = files[sim_id + group_size * sim_group]
            job['name'] = str(sim_id)
            job['bootbinary'] = binary
            job['simulation_outputs'] = []
            job['outputs'] = []
            workload['workloads'].append(job)

    left -= group_size

    benchmark_name = args.binary_dir + "_" + str(sim_group)
    workload_json = benchmark_name + ".json"
    workloads_list.append(benchmark_name)
    with open(os.path.join(base_dir, workload_json), "w") as of:
        json.dump(workload, of, indent=4)

with open('tip-intermediate/' + args.binary_dir + '-firesim-workload-specs.json', 'w') as of:
    json.dump(workloads_list, of, indent=4)
