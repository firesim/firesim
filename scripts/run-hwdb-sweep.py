#!/usr/bin/env python3

import argparse
import os
import yaml
#import json
from pathlib import Path
import tempfile
import subprocess
import signal

script_dir = os.path.dirname(__file__)

# args

desc = """Helper script that for a specific HWDB sweeps over frequencies to find the best one.
Requires the 'sourceme-manager.sh' and 'firesim managerinit' to be run (i.e. ready to do builds without this script)."""
parser = argparse.ArgumentParser(description=desc)

parser.add_argument('-bry', '--build_recipe_yaml', type=Path, help='build_recipe.yaml (must have recipe to sweep on)', required=True)
parser.add_argument('-by', '--build_yaml', type=Path, help='build.yaml (must specify all build machines, must have 1 recipe to sweep on)', required=True)
parser.add_argument('-sf', '--start_freq', type=int, help='min. freq. to sweep (inclusive)', required=True)
parser.add_argument('-ef', '--end_freq', type=int, help='max. freq. to sweep (exclusive)', required=True)
parser.add_argument('-n', '--max_build_parallelism', type=int, help='max # of build machines to use (must be <= amount specified in build_recipe.yaml)', required=True)

args = parser.parse_args()

# example usage
#
# cmdline: SCRIPT -bry bry.yaml -by by.yaml -sf 10 -sf 100 -n 4
#
# by.yaml:
#
#   # fully specified build farm (can use up to 4 machines in this case)
#   build_farm:
#    base_recipe: build-farm-recipes/externally_provisioned.yaml
#    recipe_arg_overrides:
#      default_build_dir: ...
#      build_farm_hosts:
#        - machine0
#        - machine1
#        - machine2
#        - machine3
#
#    builds_to_run:
#      - RECIPE_TO_BUILD
#
#    agfis_to_share:
#
#    share_with_accounts:
#      ...
#
# bry.yaml:
#
#   RECIPE_TO_BUILD:
#       ...

# misc funcs

def read_yaml_to_dict(filename):
  """Reads a YAML file and returns a Python dictionary."""
  # Open the file in read mode
  with open(filename, 'r') as file:
    try:
      # Use yaml.safe_load for security reasons (avoid untrusted sources)
      data = yaml.safe_load(file)
    except yaml.YAMLError as exc:
      print(f"Error parsing YAML file: {exc}")
      return None
  return data

def write_dict_to_yaml(d, filename):
  with open(filename, 'w') as file:
    data = yaml.dump(d)
    file.write(data)

def replace_item(inobj, key, replace_value):
  obj = inobj.copy()
  for k, v in obj.items():
    if isinstance(v, dict):
      obj[k] = replace_item(v, key, replace_value)
  if key in obj:
    obj[key] = replace_value
  return obj

def change_freq(initial_dict, new_freq):
  return replace_item(initial_dict, "fpga_frequency", new_freq)

def change_name(initial_dict, old_name, new_name):
  d = initial_dict.copy()
  d[new_name] = d[old_name]
  del d[old_name]
  return d

def run_grep_subprocess(directory, scope, pattern):
  """Runs grep on a directory using the subprocess module."""
  grep_command = f"find {directory} -type d -path '*{scope}*' -exec grep -rn '{pattern}' {{}} \\+"
  print(f"Running {grep_command}")
  try:
    output = subprocess.check_output(grep_command, shell=True, text=True)
    return output.strip()  # Remove trailing newlines
  except subprocess.CalledProcessError as exc:
    print(f"Grep command failed: {exc}")
    return ""

def find_latest_directory_with_string(directory_path, string):
  """
  Finds the latest directory containing a specific string within a directory.
  """

  latest_dir = None
  latest_ctime = None

  for root, directories, _ in os.walk(directory_path):
    for dir in directories:
      if string in dir:
        dir_path = os.path.join(root, dir)
        dir_ctime = os.path.getctime(dir_path)

        if latest_ctime is None or dir_ctime > latest_ctime:
          latest_dir = dir_path
          latest_ctime = dir_ctime

  return latest_dir

def round_w_base(x, base=10):
  return base * round(x/base)

def get_nary_search_range(end, start, n):
  freq_to_search = []
  freq_range = end - start

  if freq_range <= 10:
    return freq_to_search

  freq_incs = freq_range / (n + 1)
  freq_incs = round_w_base(freq_incs)
  cur_freq = start
  while cur_freq <= end:
    freq_to_search.append(cur_freq)
    cur_freq = cur_freq + freq_incs

  if end in freq_to_search:
    freq_to_search.remove(end)
  if start in freq_to_search:
    freq_to_search.remove(start)

  print(f"Range:{freq_range} Inc:{freq_incs}")

  return freq_to_search

# core

assert 'RISCV' in os.environ, "Must run sourceme-manager.sh before this script"

assert args.end_freq > args.start_freq
assert args.end_freq % 10 == 0, "Only mults of 10"
assert args.start_freq % 10 == 0, "Only mults of 10"
assert args.start_freq > 0

bry = read_yaml_to_dict(args.build_recipe_yaml)
by = read_yaml_to_dict(args.build_yaml)
assert len(bry.keys()) == 1, "Must have only 1 recipe in the build recipe"

br_name_to_sweep = list(bry.items())[0][0]

parallelism = args.max_build_parallelism
assert parallelism >= 1
print(f"Using {parallelism} build machines for sweeping")

# TODO: make this more programmatic?
# find path to grep for violated in depending on platform (string must exist in the path of the file to be grepped)
platform = bry[br_name_to_sweep]['PLATFORM']
if 'xilinx_alveo' in platform:
  report_path = 'vivado_proj/reports'
elif 'xilinx_vcu118' in platform:
  report_path = 'build/reports'
elif 'vitis' in platform:
  report_path = '' # TODO: get actual path of reports (low-pri since vitis shouldn't be used)
elif 'rhsresearch_nitefury_ii' in platform:
  report_path = '' # TODO: no reports dumped for this (search everything)
elif 'f1' in platform:
  report_path = 'build/reports'

# this is where the recursion happens

def do_builds(freq_to_search):
  # create a new temp build recipe file that has new names with freqs attached
  brds = [ change_freq(change_name(bry, br_name_to_sweep, f"{br_name_to_sweep}_{e}"), e) for e in freq_to_search ]
  #print(brds)
  new_bry = {}
  for e in brds:
    new_bry.update(e)
  #print(json.dumps(new_bry, indent=4))

  # create a new temp build file that has names with freqs attached
  br_names = [ f"{br_name_to_sweep}_{e}" for e in freq_to_search ]
  new_by = by.copy()
  new_by['builds_to_run'] = br_names
  #print(json.dumps(new_by, indent=4))

  with tempfile.TemporaryDirectory() as temp_dir:
    # print both to files
    write_dict_to_yaml(new_bry, f"{temp_dir}/tmp_bry.yaml")
    write_dict_to_yaml(new_by, f"{temp_dir}/tmp_by.yaml")

    def execute(cmd):
      # NOTE: if shell=True then the cmd needs to be a single string, else a list of words
      try:
        popen = subprocess.Popen(cmd, stdout=subprocess.PIPE, universal_newlines=True, env=os.environ, shell=True, preexec_fn=os.setsid)
        for stdout_line in iter(popen.stdout.readline, ""):
            yield stdout_line
        popen.stdout.close()
        return_code = popen.wait()
        if return_code:
            raise subprocess.CalledProcessError(return_code, cmd)
      except KeyboardInterrupt:
        os.killpg(os.getpgid(popen.pid), signal.SIGTERM)
        exit(1)

    # build bitstream w/ those files
    print("Executing FireSim builds")
    for l in execute([f'firesim buildbitstream -r {temp_dir}/tmp_bry.yaml -b {temp_dir}/tmp_by.yaml']):
      print(l, end="")
    print("Done executing FireSim builds")

  # check to see if hwdb entry exists for files (if does, then double check by grepping violated in most recent build results)
  # if doesn't then it failed
  #fp_d = {} - key=name, value=bool:passed or not
  print("Checking FireSim build output")
  fp_d = {}
  for br_name in br_names:
    if os.path.exists(f"{script_dir}/../deploy/built-hwdb-entries/{br_name}"):
      print(f"{br_name} exists... checking for VIOLATED in logs...")
      # get most recent run that has br_name in it
      latest_dir = find_latest_directory_with_string(f"{script_dir}/../deploy/results-build", br_name)
      results = run_grep_subprocess(latest_dir, report_path, "VIOLATED")
      if results == "":
        fp_d[br_name] = True
      else:
        fp_d[br_name] = False
    else:
      fp_d[br_name] = False

  return fp_d

print(f"Starting sweep...")

# basically want to do a n-ary search where n is parallelism+1.
# i.e. binary search is where parallelism is 1
# i.e. 3-ary search is where parallelism is 2

# adapted from: https://medium.com/@sreeku.ralla/improving-binary-to-n-ary-search-839d89e14703
def nary_search(low, high, lst, n):
  best_pass_idx = -1

  while high > low:
    inc = max((high - low) // (n + 1), 1)

    print(f"HighIdx: {high} LowIdx:{low} Inc:{inc}")

    check_idxs = sorted(list(set(range(low, high, inc)) | set({low, high})))
    print(f"CheckIdxsPreTrim: {check_idxs}")
    if len(check_idxs) > n:
      trim_end_idx = (len(check_idxs) - n) // 2
      # take the middle idxs
      check_idxs = check_idxs[trim_end_idx:]
      check_idxs = check_idxs[:n]
    print(f"CheckIdxs: {check_idxs}")

    search_freqs = [ lst[e] for e in check_idxs ]
    freq_2_idx = {}
    for e in check_idxs:
      freq_2_idx[lst[e]] = e
    print(f"SearchFreqs: {search_freqs}")

    # build bitstreams for selected stuff
    results = do_builds(set(search_freqs))

    # testing
    #results = {}
    #for f in search_freqs:
    #  results[f"name_{f}"] = True if f < 35 else False

    print(f"Build results: {results}")

    # know the failures and passes (determine next start/end)
    # between the highest pass and lowest failure is the new low/high, respectively
    highest_pass_idx = low
    lowest_fail_idx = high
    for k, v in results.items():
      freq = int(k.split('_')[-1])
      idx = freq_2_idx[freq]
      passed = v

      if passed:
        if idx > highest_pass_idx:
          low = idx + 1
          highest_pass_idx = idx
          best_pass_idx = idx
      else:
        if idx < lowest_fail_idx:
          high = idx - 1
          lowest_fail_idx = idx

  return best_pass_idx

rang = list(range(args.start_freq, args.end_freq, 10))
print(f"All frequencies to search: {rang}")
best_freq_idx = nary_search(0, len(rang)-1, rang, parallelism)
if best_freq_idx == -1:
  print(">>> No freq. passed <<<")
else:
  best_freq = rang[best_freq_idx]
  print(f">>> Highest freq. that passed was: {best_freq} <<<")
