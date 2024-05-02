#!/usr/bin/env python3


import yaml
import os
import sys
import argparse

parser = argparse.ArgumentParser(description="Arguments for metasim based CI")
parser.add_argument("--minimal", type=bool, default=False, help="run reduced number of tests")
args = parser.parse_args()

SCRIPTSDIR = os.path.dirname(os.path.abspath(__file__))
DEPLOYDIR=os.path.join(SCRIPTSDIR, "../deploy")
TESTDIR=os.path.join(DEPLOYDIR, "fireaxe-tests")
SIMDIR="/scratch/joonho.whangbo/coding/firesim-out-multifpga"


class OverrideParams:
  name: str
  sim_dir: str
  topo: str
  batch_size: int
  workload: str

  def __init__(self, name: str, sim_dir: str, topo: str, batch_size: int, workload: str) -> None:
    self.name = name
    self.sim_dir = sim_dir
    self.topo = topo
    self.batch_size = batch_size
    self.workload = workload

def bash(cmd):
  fail = os.system(cmd)
  print(f'$ {cmd}')
  if fail:
      print(f'[*] failed to execute {cmd}')
      sys.exit(1)


def read_default_config_runtime():
  with open(os.path.join(DEPLOYDIR, "config_runtime.yaml")) as cfg:
    try:
      config = yaml.safe_load(cfg)
      return config
    except yaml.YAMLError as exc:
      print(exc)
      exit(1)

def update_config_runtime(default_config: dict, opm: OverrideParams):
  default_config['run_farm']['recipe_arg_overrides']['default_simulation_dir'] = opm.sim_dir
  default_config['target_config']['topology'] = opm.topo
  default_config['partitioning']['batch_size'] = opm.batch_size
  default_config['workload']['workload_name'] = opm.workload
  return default_config


def run_test(pfx: str, default_config: dict, override_params: OverrideParams):
  cfg_runtime_yaml = update_config_runtime(default_config, override_params)
  cfg_runtime_name = f"{pfx}_config_runtime.yaml"
  cfg_runtime_path = os.path.join(TESTDIR, cfg_runtime_name)
  with open(cfg_runtime_path, "w") as f:
    yaml.dump(cfg_runtime_yaml, f)

  bash(f"firesim infrasetup -c {cfg_runtime_path}")
  bash(f"firesim runworkload -c {cfg_runtime_path}")

  print("DONE")

def setup_tests():
  if not os.path.exists(TESTDIR):
    os.makedirs(TESTDIR)

def main():
  setup_tests()
  default_cfg = read_default_config_runtime()

  base_tests = [
      OverrideParams("rocket-tile",
                     os.path.join(SIMDIR, "ci-rocket"),
                     "fireaxe_split_rocket_tile_from_soc_config",
                     1,
                     "hello.json"),
      OverrideParams("boomtracerv-tile",
                     os.path.join(SIMDIR, "ci-boomtracerv"),
                     "fireaxe_split_largeboomtracerv_tile_from_soc_config",
                     1,
                     "hello.json"),
      OverrideParams("mempress-accel",
                     os.path.join(SIMDIR, "ci-mempress"),
                     "fireaxe_split_mempress_sbus16_accel_from_soc_config",
                     1,
                     "mempress.json"),
      OverrideParams("sha3-accel",
                     os.path.join(SIMDIR, "ci-sha3"),
                     "fireaxe_split_sha3_slowmem_accel_from_soc_config",
                     1,
                     "sha3-bare-rocc.json")
      ]

  for test in base_tests:
    run_test(test.name, default_cfg, test)
    if args.minimal:
      break

  topology_split_tests = [
      OverrideParams("rocket-dual-tile",
                     os.path.join(SIMDIR, "ci-rocket-dual-tile-3fpga"),
                     "fireaxe_split_dual_rocket_tile_from_soc_config",
                     1,
                     "mt-hello.json"),
      OverrideParams("rocket-quad-tile-2fpga",
                     os.path.join(SIMDIR, "ci-rocket-quad-tile-2fpga"),
                     "fireaxe_split_quad_rockettiles_from_soc_config",
                     1,
                     "mt-hello-4.json"),
      OverrideParams("rocket-quad-tile-3fpga",
                     os.path.join(SIMDIR, "ci-rocket-quad-tile-3fpga"),
                     "firesim_split_quad_rocket_tiles_from_soc_3fpga_config",
                     1,
                     "mt-hello-4.json"),
      OverrideParams("rocket-quad-sha3-accel-2fpga",
                     os.path.join(SIMDIR, "ci-sha3-quad-accel-2fpga"),
                     "fireaxe_split_quad_sha3accels_from_soc_config",
                     1,
                     "sha3-bare-rocc.json"),
      ]

  for test in topology_split_tests:
    run_test(test.name, default_cfg, test)
    if args.minimal:
      break


  fame5_partition_tests = [
      OverrideParams("fame5-rocket-dual-tile",
                     os.path.join(SIMDIR, "ci-fame5-rocket-dual-tile-2fpga"),
                     "fireaxe_split_fame5_dual_rocket_tile_from_soc_config",
                     1,
                     "mt-hello.json"),
      OverrideParams("fame5-rocket-quad-tile",
                     os.path.join(SIMDIR, "ci-fame5-rocket-quad-tile-2fpga"),
                     "fireaxe_split_fame5_quad_rocket_tile_from_soc_config",
                     1,
                     "mt-hello-4.json"),
      ]

  for test in fame5_partition_tests:
    run_test(test.name, default_cfg, test)
    if args.minimal:
      break

  gemmini_tests = [ "conv", "matmul", "padded", "raw", "resadd", "transpose" ]
  gemmini_override_params = [ 
    OverrideParams("gemmini-accel",
                   os.path.join(SIMDIR, f"ci-gemmini-{x}"),
                   "fireaxe_split_gemmini_from_soc_config", 
                   1,
                   f"gemmini-{x}-baremetal.json")
     for x in gemmini_tests
  ]

  for test in gemmini_override_params:
    run_test(test.name, default_cfg, test)
    if args.minimal:
      break


if __name__ == "__main__":
  main()
