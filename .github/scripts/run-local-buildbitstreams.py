#!/usr/bin/env python3

import sys
from pathlib import Path
from fabric.api import prefix, run, settings, execute # type: ignore
import os
from github import Github
import base64
import time
import argparse

from ci_variables import ci_env

from typing import List

GH_REPO = 'firesim-public-bitstreams'
GH_ORG = 'firesim'
URL_PREFIX = f"https://raw.githubusercontent.com/{GH_ORG}/{GH_REPO}"

build_location = "/scratch/buildbot/fstmp"

# taken from https://stackoverflow.com/questions/63427607/python-upload-files-directly-to-github-using-pygithub
# IMPORTANT: only works for binary files! (i.e. tar.gz files)
def upload_binary_file(local_file_path, gh_file_path):
    print(f":DEBUG: Attempting to upload {local_file_path} to {gh_file_path}")

    g = Github(ci_env['PERSONAL_ACCESS_TOKEN'])

    repo = g.get_repo(f'{GH_ORG}/{GH_REPO}')
    all_files = []
    contents = repo.get_contents("")
    while contents:
        file_content = contents.pop(0)
        if file_content.type == "dir":
            contents.extend(repo.get_contents(file_content.path))
        else:
            file = file_content
            all_files.append(str(file).replace('ContentFile(path="','').replace('")',''))

    with open(local_file_path, 'rb') as file:
        content = file.read()

    tries = 10
    delay = 15
    msg = f"Committing files from {ci_env['GITHUB_SHA']}"
    upload_branch = 'main'
    r = None

    # Upload to github
    git_file = gh_file_path
    if git_file in all_files:
        contents = repo.get_contents(git_file)
        for n in range(tries):
            try:
                r = repo.update_file(contents.path, msg, content, contents.sha, branch=upload_branch)
                break
            except Exception as e:
                print(f"Got exception: {e}")
                time.sleep(delay)
        assert r is not None, f"Unable to poll 'update_file' API {tries} times"
        print(f"Updated: {git_file}")
    else:
        for n in range(tries):
            try:
                r = repo.create_file(git_file, msg, content, branch=upload_branch)
                break
            except Exception as e:
                print(f"Got exception: {e}")
                time.sleep(delay)
        assert r is not None, f"Unable to poll 'create_file' API {tries} times"
        print(f"Created: {git_file}")

    return r['commit'].sha

def run_local_buildbitstreams():
    """ Runs local buildbitstreams"""

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    manager_fsim_dir = ci_env['REMOTE_WORK_DIR']
    with prefix(f"cd {manager_fsim_dir}"):

        with prefix('source sourceme-manager.sh --skip-ssh-setup'):

            # return a copy of config_build.yaml w/ hwdb entry(s) uncommented + new build dir
            def modify_config_build(hwdb_entries_to_gen: List[str]) -> str:
                build_yaml = f"{manager_fsim_dir}/deploy/config_build.yaml"
                copy_build_yaml = f"{manager_fsim_dir}/deploy/config_build_{hash(tuple(hwdb_entries_to_gen))}.yaml"
                build_yaml_lines = open(build_yaml).read().split("\n")
                with open(copy_build_yaml, "w") as byf:
                    for line in build_yaml_lines:
                        if "- firesim" in line:
                            # comment out AWS specific lines
                            byf.write("# " + line + '\n')
                        elif 'default_build_dir:' in line:
                            byf.write(line.replace('null', build_location) + '\n')
                        elif len([True for hwdb_entry_to_gen in hwdb_entries_to_gen if (f"- {hwdb_entry_to_gen}" in line)]):
                            # remove comment
                            byf.write(line.replace("# ", '') + '\n')
                        else:
                            byf.write(line + '\n')
                return copy_build_yaml

            def add_host_list(build_yaml: str, hostlist: List[str]) -> str:
                copy_build_yaml = f"{manager_fsim_dir}/deploy/config_build_{hash(tuple(hostlist))}.yaml"
                build_yaml_lines = open(build_yaml).read().split("\n")
                with open(copy_build_yaml, "w") as byf:
                    for line in build_yaml_lines:
                        if "build_farm_hosts:" in line and not "#" in line:
                            byf.write(line + '\n')
                            start_space_idx = line.index('b')
                            for host in hostlist:
                                byf.write((' ' * (start_space_idx + 4)) + f"- {host}" + '\n')
                        elif '- localhost' in line and not '#' in line:
                            byf.write("# " + line + '\n')
                        else:
                            byf.write(line + '\n')
                return copy_build_yaml

            def build_upload(build_yaml: str, hwdb_entries: List[str], platforms: List[str]) -> List[str]:

                print(f"Printing {build_yaml}...")
                run(f"cat {build_yaml}")

                rc = 0
                with settings(warn_only=True):
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    build_result = run(f"timeout 10h firesim buildbitstream -b {build_yaml} --forceterminate", pty=False)
                    rc = build_result.return_code

                if rc != 0:
                    log_lines = 200
                    print(f"Buildbitstream failed. Printing {log_lines} of last log file:")
                    run(f"""LAST_LOG=$(ls | tail -n1) && if [ -f "$LAST_LOG" ]; then tail -n{log_lines} $LAST_LOG; fi""")
                    sys.exit(rc)

                hwdb_entry_dir = f"{manager_fsim_dir}/deploy/built-hwdb-entries"
                links = []

                for hwdb_entry_name, platform in zip(hwdb_entries, platforms):
                    hwdb_entry = f"{hwdb_entry_dir}/{hwdb_entry_name}"

                    print(f"Printing {hwdb_entry}...")
                    run(f"cat {hwdb_entry}")

                    with open(hwdb_entry, 'r') as hwdbef:
                        lines = hwdbef.readlines()
                        for line in lines:
                            if "bitstream_tar:" in line:
                                file_path = Path(line.strip().split(' ')[1].replace('file://', '')) # 2nd element (i.e. the path) (no URI)
                                file_name = f"{platform}/{hwdb_entry_name}.tar.gz"
                                run(f"shasum -a 256 {file_path}")
                                sha = upload_binary_file(file_path, file_name)
                                link = f"{URL_PREFIX}/{sha}/{file_name}"
                                print(f"Uploaded bitstream_tar for {hwdb_entry_name} to {link}")
                                links.append(link)
                                break

                return links

            relative_hwdb_path = "deploy/sample-backup-configs/sample_config_hwdb.yaml"
            sample_hwdb_filename = f"{manager_fsim_dir}/{relative_hwdb_path}"

            def replace_in_hwdb(hwdb_entry_name: str, link: str) -> None:
                # replace the sample hwdb's bit line only
                sample_hwdb_lines = open(sample_hwdb_filename).read().split('\n')

                with open(sample_hwdb_filename, "w") as sample_hwdb_file:
                    match_bit = False
                    for line in sample_hwdb_lines:
                        if hwdb_entry_name in line.strip().split(' ')[0].replace(':', ''):
                            # hwdb entry matches key name
                            match_bit = True
                            sample_hwdb_file.write(line + '\n')
                        elif match_bit == True:
                            if ("bitstream_tar:" in line.strip().split(' ')[0]):
                                # only replace this bit
                                match_bit = False

                                new_bit_line = f"    bitstream_tar: {link}"
                                print(f"Replacing {line.strip()} with {new_bit_line}")

                                # print out the bit line
                                sample_hwdb_file.write(new_bit_line + '\n')
                            else:
                                sys.exit("::ERROR:: Something went wrong")
                        else:
                            # if no match print other lines
                            sample_hwdb_file.write(line + '\n')

                    if match_bit == True:
                        sys.exit(f"::ERROR:: Unable to replace URL for {hwdb_entry_name} in {sample_hwdb_filename}")

            # could potentially use knight/ferry in the future (currently unused since they are currently overloaded)
            hosts = {
                ("localhost", "vitis:2022.1"),
                ("jktgz", "vitis:2022.1"),
                ("jktqos", "vivado:2021.1"),
                ("firesim1", "vivado:2019.1"),
            }

            def do_builds(batch_hwdbs):
                assert len(hosts) >= len(batch_hwdbs), f"Need at least {len(batch_hwdbs)} hosts to run builds"

                # map hwdb tuple to hosts
                hwdb_2_host = {}
                for hwdb in batch_hwdbs:
                    buildtool_version = hwdb[2]
                    for host in hosts:
                        if host[1] == buildtool_version:
                            if not host[0] in hwdb_2_host.values():
                                hwdb_2_host[hwdb[0]] = host[0]

                assert len(hwdb_2_host) == len(batch_hwdbs), "Unable to map hosts to hwdb build"

                hwdbs_ordered = [hwdb[0] for hwdb in batch_hwdbs]
                platforms_ordered = [hwdb[1] for hwdb in batch_hwdbs]
                hosts_ordered = hwdb_2_host.values()

                print("Mappings")
                print(f"HWDBS: {hwdbs_ordered}")
                print(f"Platforms: {platforms_ordered}")
                print(f"Hosts: {hosts_ordered}")

                copy_build_yaml = modify_config_build(hwdbs_ordered)
                copy_build_yaml_2 = add_host_list(copy_build_yaml, hosts_ordered)
                links = build_upload(copy_build_yaml_2, hwdbs_ordered, platforms_ordered)
                for hwdb, link in zip(hwdbs_ordered, links):
                    replace_in_hwdb(hwdb, link)

                # wipe old data
                for host in hosts_ordered:
                    run(f"ssh {host} rm -rf {build_location}")

            # same order as in config_build.yaml
            # hwdb_entry_name, platform_name, buildtool:version
            batch_hwdbs_in = [
                ("vitis_firesim_rocket_singlecore_no_nic", "vitis", "vitis:2022.1"),
                ("vitis_firesim_gemmini_rocket_singlecore_no_nic", "vitis", "vitis:2022.1"),
                ("alveo_u250_firesim_rocket_singlecore_no_nic", "xilinx_alveo_u250", "vivado:2021.1"),
                ("xilinx_vcu118_firesim_rocket_singlecore_4GB_no_nic", "xilinx_vcu118", "vivado:2019.1"),
            ]

            do_builds(batch_hwdbs_in)

            batch_hwdbs_in = [
                ("alveo_u280_firesim_rocket_singlecore_no_nic", "xilinx_alveo_u280", "vivado:2021.1"),
            ]

            do_builds(batch_hwdbs_in)

            print(f"Printing {sample_hwdb_filename}...")
            run(f"cat {sample_hwdb_filename}")

            # copy back to workspace area so you can PR it
            run(f"cp -f {sample_hwdb_filename} {ci_env['GITHUB_WORKSPACE']}/{relative_hwdb_path}")

if __name__ == "__main__":
    execute(run_local_buildbitstreams, hosts=["localhost"])
