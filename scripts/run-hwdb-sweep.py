#!/usr/bin/env python3

# this is a helper script that for a specific hwdb sweeps over frequencies to find the best

import argparse
import os
import yaml
import json
from pathlib import Path
import tempfile
import subprocess

script_dir = os.path.dirname(__file__)

parser = argparse.ArgumentParser(description='Process a given filename.')
parser.add_argument('--br_yaml', type=Path, help='', required=True)
parser.add_argument('--by', type=Path, help='', required=True)
parser.add_argument('--start_freq', type=int, help='Min. freq. to sweep', required=True)
parser.add_argument('--end_freq', type=int, help='Max. freq. to sweep', required=True)
parser.add_argument('--max_build_parallelism', type=int, help='Max # of build machines to use', required=True)

args = parser.parse_args()

assert args.end_freq > args.start_freq
assert args.end_freq % 10 == 0, "Only mults of 10"
assert args.start_freq % 10 == 0, "Only mults of 10"

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

def run_grep_subprocess(directory, pattern):
    """Runs grep on a directory using the subprocess module."""
    grep_command = f"grep -rn {pattern} {directory}"  # Construct the grep command
    try:
        output = subprocess.check_output(grep_command, shell=True, text=True)
        return output.strip()  # Remove trailing newlines
    except subprocess.CalledProcessError as exc:
        print(f"Grep command failed: {exc}")
        return None

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

br = read_yaml_to_dict(args.br_yaml)
by = read_yaml_to_dict(args.by)
assert len(br.keys()) == 1, "Must have only 1 recipe in the build recipe"

br_name = list(br.items())[0][0]

build_farm_parallelism = 0
if 'aws_ec2.yaml' in by['build_farm']['base_recipe']:
  build_farm_parallelism = 100 # arb. large number representing inf
elif 'externally_provisioned.yaml' in by['build_farm']['base_recipe']:
  # TODO: expects that the key exists (what about when there are no overrides)
  build_farm_parallelism = len(by['build_farm']['recipe_arg_overrides']['build_farm_hosts'])
else:
  raise ValueError("Unsupported build farm type")

print(f"Found build_farm_parallelism of {build_farm_parallelism} from build farm")

def myround(x, base=10):
  return base * round(x/base)

# this is where the recursion happens

def do_builds(freq_to_search):
  print(f"Searching through {freq_to_search} frequencies")

  # create a new temp build recipe file that has new names with freqs attached
  brds = [ change_freq(change_name(br, br_name, f"{br_name}_{e}"), e) for e in freq_to_search ]
  #print(brds)
  new_bry = {}
  for e in brds:
    new_bry.update(e)
  #print(json.dumps(new_bry, indent=4))

  br_names = [ f"{br_name}_{e}" for e in freq_to_search ]
  new_by = by.copy()
  new_by['builds_to_run'] = br_names
  #print(json.dumps(new_by, indent=4))

  # print both to files
  write_dict_to_yaml(new_bry, "tmp_bry.yaml")
  write_dict_to_yaml(new_by, "tmp_by.yaml")

  def execute(cmd):
    popen = subprocess.Popen(cmd, stdout=subprocess.PIPE, universal_newlines=True, env=os.environ)
    for stdout_line in iter(popen.stdout.readline, ""):
        yield stdout_line
    popen.stdout.close()
    return_code = popen.wait()
    if return_code:
        raise subprocess.CalledProcessError(return_code, cmd)

  # build bitstream w/ those files
  execute(['firesim', 'buildbitstream', '-c', 'tmp_bry.yaml', '-c', 'tmp_by.yaml'])

  # check to see if hwdb entry exists for files (if does, then double check by grepping violated in most recent build results)
  # if doesn't then it failed
  #fp_d = {} - key=name, value=bool:passed or not
  fp_d = {}
  for br_name in br_names:
    if os.path.exists(f"{script_dir}/../deploy/built-hwdb-entries/{br_name}"):
      print(f"{br_name} exists... checking for VIOLATED in logs...")
      # get most recent run that has br_name in it
      latest_dir = find_latest_directory_with_string(f"{script_dir}/../deploy/results-build", br_name)
      results = run_grep_subprocess(latest_dir, "VIOLATED")
      if results == "":
        fp_d[br_name] = True
      else:
        fp_d[br_name] = False
    else:
      fp_d[br_name] = False

  return fp_d

tried_s = {} # set of freqs. tried

end_f = args.end_freq
start_f = args.start_freq

while True:
  freq_range = end_f - start_f
  freq_incs = freq_range / min(args.max_build_parallelism, build_farm_parallelism)
  freq_incs = myround(freq_incs)

  freq_to_search = []
  cur_freq = start_f
  while cur_freq <= end_f:
    freq_to_search.append(cur_freq)
    cur_freq = cur_freq + freq_incs

  fs_s = set(freq_to_search) - tried_s
  if fs_s:
    fp_d = do_builds(fs_s)
    # add checked freqs to tried set
    tried_s = tried_s + fs_s
  else:
    break

  # know the failures and passes (determine next start/end)
  highest_freq_that_passed = args.start_freq
  lowest_freq_that_failed = args.end_freq
  for k, v in fp_d.items():
    freq = int(k.split('_')[-1])
    if (freq >= highest_freq_that_passed) and v:
      highest_freq_that_passed = freq
    if (freq <= lowest_freq_that_failed) and not v:
      lowest_freq_that_failed = freq

  print(f">>> >>> Last highest freq. that passed = {highest_freq_that_passed}")

  start_f = highest_freq_that_passed
  end_f = lowest_freq_that_failed
