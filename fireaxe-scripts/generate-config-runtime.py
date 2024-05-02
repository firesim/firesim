#!/usr/bin/env python3

import yaml
import argparse

parser = argparse.ArgumentParser(description="Arguments for generating config runtime")
parser.add_argument("--sim-dir", type=str, required=True, help="Abs path to sim-dir")
parser.add_argument("--topology", type=str, required=True, help="topology field")
parser.add_argument("--tip-enable", type=bool, default=False, required=False, help="enable TIP profiling?")
parser.add_argument("--tip-core-width", type=int, default=2, required=False, help="TIP core width")
parser.add_argument("--tip-rob-depth", type=int, default=64, required=False, help="TIP ROB depth")
parser.add_argument("--partition-seed", type=int, default=1, required=True, help="partition init seed count")
parser.add_argument("--workload-name", type=str, default="hello.json", required=True, help="Workload specification")
parser.add_argument("--out-config-file", type=str, required=True, help="Name of the generated config_runtime.yaml")
parser.add_argument("--default-hw-config", type=str, default="xilinx_u250_firesim_rocket", help="Default HW Config")
args = parser.parse_args()

with open('../deploy/config_runtime.yaml', 'r') as f:
    cfg = yaml.safe_load(f)

    TIP_WORKER_CONFIG='ORACLE-OUT,400,100,100,g100,f100'
    TIP_WORKER = "oracle2" if args.tip_core_width > 4 else "oracle"

    cfg['run_farm']['recipe_arg_overrides']['default_simulation_dir'] = args.sim_dir
    cfg['target_config']['topology'] = args.topology
    cfg['target_config']['default_hw_config'] = args.default_hw_config
    cfg['tip_tracing']['enable'] = args.tip_enable
    cfg['tip_tracing']['core'] = f"{args.tip_core_width},{args.tip_rob_depth}"
    cfg['tip_tracing']['worker'] = f"{TIP_WORKER},{TIP_WORKER_CONFIG}"
    cfg['partitioning']['batch_size'] = args.partition_seed
    cfg['workload']['workload_name'] = args.workload_name

    with open(args.out_config_file, 'w') as g:
        yaml.dump(cfg, g, sort_keys=False)
