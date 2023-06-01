#!/usr/bin/env python3

import sys
from pathlib import Path
from fabric.api import prefix, run, settings, execute # type: ignore
import os
from github import Github
import base64
import time

from ci_variables import ci_env

from typing import List

GH_REPO = 'firesim-public-bitstreams'
GH_ORG = 'firesim'
URL_PREFIX = f"https://raw.githubusercontent.com/{GH_ORG}/{GH_REPO}"

build_location = "/scratch/buildbot/fstmp"

# taken from https://stackoverflow.com/questions/63427607/python-upload-files-directly-to-github-using-pygithub
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
        content = base64.b64encode(file.read()).decode("utf-8")

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

        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):

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
                            elif ("bitstream_tar:" in line.strip().split(' ')[0]):
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

            batch_hwdbs = [
                "vitis_firesim_gemmini_rocket_singlecore_no_nic", # 2022.1
                "alveo_u250_firesim_rocket_singlecore_no_nic", # 2021.1
                #"alveo_u280_firesim_rocket_singlecore_no_nic", # 2021.1
                "xilinx_vcu118_firesim_rocket_singlecore_4GB_no_nic", # 2019.1
                "vitis_firesim_rocket_singlecore_no_nic", # 2022.1
            ]
            batch_platforms = [
                "vitis",
                "xilinx_alveo_u250",
                # "xilinx_alveo_u280",
                "xilinx_vcu118",
                "vitis",
            ]
            hosts = [
                "jktgz",
                "jktqos",
                "firesim1",
                #"knight", # could use but might be overloaded
                #"ferry", # could use but might be overloaded
            ] # localhost is always last

            assert len(hosts) + 1 >= len(batch_hwdbs)
            assert len(batch_hwdbs) == len(batch_platforms)

            copy_build_yaml = modify_config_build(batch_hwdbs)
            copy_build_yaml_2 = add_host_list(copy_build_yaml, hosts)
            links = build_upload(copy_build_yaml_2, batch_hwdbs, batch_platforms)
            for hwdb, link in zip(batch_hwdbs, links):
                replace_in_hwdb(hwdb_entry_name, link)

            print(f"Printing {sample_hwdb_filename}...")
            run(f"cat {sample_hwdb_filename}")

            # copy back to workspace area so you can PR it
            run(f"cp -f {sample_hwdb_filename} {ci_env['GITHUB_WORKSPACE']}/{relative_hwdb_path}")

            # wipe old data
            for host in hosts:
                run(f"ssh {host} rm -rf {build_location}")

if __name__ == "__main__":
    execute(run_local_buildbitstreams, hosts=["localhost"])
