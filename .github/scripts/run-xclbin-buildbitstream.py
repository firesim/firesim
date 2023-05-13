#!/usr/bin/env python3

import sys
from pathlib import Path
from fabric.api import prefix, run, settings, execute # type: ignore
import os
from github import Github

from ci_variables import ci_env

GH_REPO = 'firesim-public-bitstreams'
GH_ORG = 'firesim'
URL_PREFIX = f"https://raw.githubusercontent.com/{GH_ORG}/{GH_REPO}"

# taken from https://stackoverflow.com/questions/63427607/python-upload-files-directly-to-github-using-pygithub
def upload_file(local_file_path, gh_file_path):
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

    with open(local_file_path, 'r') as file:
        content = file.read()

    # Upload to github
    git_file = gh_file_path
    if git_file in all_files:
        contents = repo.get_contents(git_file)
        content, commit = repo.update_file(contents.path, "committing files", content, contents.sha, branch="master")
        print(git_file + ' UPDATED')
    else:
        content, commit = repo.create_file(git_file, "committing files", content, branch="master")
        print(git_file + ' CREATED')

    return commit.commit.sha

def run_xclbin_buildbitstream():
    """ Runs Xclbin buildbitstream"""

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    relative_hwdb_path = f"deploy/sample-backup-configs/sample_config_hwdb.yaml"

    manager_fsim_dir = ci_env['GITHUB_WORKSPACE']
    with prefix(f"cd {manager_fsim_dir}"):
        run("./build-setup.sh --skip-validate")

        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):

            run("firesim managerinit --platform vitis")

            # modify config_build.yaml
            build_yaml = f"{manager_fsim_dir}/deploy/config_build.yaml"
            build_yaml_lines = open(build_yaml).read().split("\n")
            with open(build_yaml, "w") as byf:
                for line in build_yaml_lines:
                    if "- firesim" in line:
                        # comment out AWS specific lines
                        byf.write("# " + line + '\n')
                    elif "- vitis_firesim" in line:
                        # remove comment on vitis line
                        byf.write(line.replace("# ", '') + '\n')
                    elif 'default_build_dir:' in line:
                        byf.write(line.replace('null', f"{manager_fsim_dir}/tmpbuildarea") + '\n')
                    else:
                        byf.write(line + '\n')

            print(f"Printing {build_yaml}...")
            run(f"cat {build_yaml}")

            rc = 0
            with settings(warn_only=True):
                # pty=False needed to avoid issues with screen -ls stalling in fabric
                build_result = run("timeout 10h firesim buildbitstream --forceterminate", pty=False)
                rc = build_result.return_code

            if rc != 0:
                log_lines = 200
                print(f"Buildbitstream failed. Printing {log_lines} of last log file:")
                run(f"""LAST_LOG=$(ls | tail -n1) && if [ -f "$LAST_LOG" ]; then tail -n{log_lines} $LAST_LOG; fi""")
                sys.exit(rc)
            else:
                hwdb_entry_dir = f"{manager_fsim_dir}/deploy/built-hwdb-entries"
                built_hwdb_entries = [x for x in os.listdir(hwdb_entry_dir) if os.path.isfile(os.path.join(hwdb_entry_dir, x))]

                hwdb_to_link = {}
                for hwdb in built_hwdb_entries:
                    print(f"Printing {hwdb}")
                    run(f"cat {hwdb_entry_dir}/{hwdb}")

                    with open(f"{hwdb_entry_dir}/{hwdb}") as hwdbef:
                        lines = hwdbef.readlines()
                        for line in lines:
                            if "xclbin:" in line:
                                file_path = Path(line.strip().split(' ')[1]) # 2nd element
                                file_name = f"vitis/{hwdb}.xclbin"
                                sha = upload_file(file_path, file_name)
                                link = f"{URL_PREFIX}/{sha}/{file_name}"
                                print(f"Uploaded xclbin for {hwdb} to {link}")
                                hwdb_to_link[hwdb] = link

                # parse the output yamls, replace the sample hwdb's xclbin line only
                sample_hwdb_filename = f"{manager_fsim_dir}/{relative_hwdb_path}"
                for hwdb in built_hwdb_entries:
                    sample_hwdb_lines = open(sample_hwdb_filename).read().split('\n')

                    with open(sample_hwdb_filename, "w") as sample_hwdb_file:
                        match_xclbin = False
                        for line in sample_hwdb_lines:
                            if hwdb in line.strip().split(' ')[0].replace(':', ''):
                                # hwdb entry matches key name
                                match_xclbin = True
                                sample_hwdb_file.write(line + '\n')
                            elif match_xclbin == True and ("xclbin:" in line.strip().split(' ')[0]):
                                # only replace this xclbin
                                match_xclbin = False

                                new_xclbin_line = f"    xclbin: {hwdb_to_link[hwdb]}"
                                print(f"Replacing {line.strip()} with {new_xclbin_line}")

                                # print out the xclbin line
                                sample_hwdb_file.write(new_xclbin_line + '\n')
                            else:
                                # if no match print other lines
                                sample_hwdb_file.write(line + '\n')

                        if match_xclbin == True:
                            sys.exit("::ERROR:: Unable to find matching xclbin key for HWDB entry")

                print(f"Printing {sample_hwdb_filename}...")
                run(f"cat {sample_hwdb_filename}")

                # copy back to workspace area so you can PR it
                run(f"cp -f {sample_hwdb_filename} {ci_env['GITHUB_WORKSPACE']}/{relative_hwdb_path}")

if __name__ == "__main__":
    execute(run_xclbin_buildbitstream, hosts=["localhost"])
