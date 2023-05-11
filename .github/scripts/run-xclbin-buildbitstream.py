#!/usr/bin/env python3

import sys
from pathlib import Path
from fabric.api import prefix, run, settings, execute # type: ignore
import os
from botocore.exceptions import ClientError
import boto3

from ci_variables import ci_env

# taken from: https://boto3.amazonaws.com/v1/documentation/api/latest/guide/s3-uploading-files.html
def upload_file(file_name, bucket, object_name=None):
    """Upload a file to an S3 bucket

    :param file_name: File to upload
    :param bucket: Bucket to upload to
    :param object_name: S3 object name. If not specified then file_name is used
    :return: True if file was uploaded, else False
    """

    # If S3 object_name was not specified, use file_name
    if object_name is None:
        object_name = os.path.basename(file_name)

    # Upload the file
    s3_client = boto3.client('s3')
    try:
        response = s3_client.upload_file(file_name, bucket, object_name)
    except ClientError as e:
        print(e)
        return False
    return True

def run_xclbin_buildbitstream():
    """ Runs Xclbin buildbitstream"""

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    manager_fsim_dir = ci_env['GITHUB_WORKSPACE']
    with prefix(f"cd {manager_fsim_dir}"):
        run("./build-setup.sh --skip-validate")

        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):

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
                    else:
                        byf.write(line + '\n')

            run(f"Printing {build_yaml}...")
            run(f"cat {build_yaml}")

            rc = 0
            with settings(warn_only=True):
                # pty=False needed to avoid issues with screen -ls stalling in fabric
                rc = run("timeout 10h firesim buildbitstream --forceterminate", pty=False).return_code

            if rc != 0:
                print("Buildbitstream failed")
                sys.exit(rc)
            else:
                hwdb_entry_dir = f"{manager_fsim_dir}/deploy/built-hwdb-entries"
                built_hwdb_entries = [x for x in os.listdir(hwdb_entry_dir) if os.path.isfile(os.path.join(hwdb_entry_dir, x))]

                hwdb_to_link = {}
                for hwdb in built_hwdb_entries:
                    with open(f"{hwdb_entry_dir}/{hwdb}") as hwdbef:
                        lines = hwdbef.readlines()
                        for line in lines:
                            if "xclbin:" in line:
                                file_path = Path(line.strip().split(' ')[1]) # 2nd element
                                file_name = f"{hwdb}_{ci_env['GITHUB_SHA'][0:6]}.xclbin"
                                if not upload_file(file_path, ci_env['AWS_BUCKET_NAME'], file_name):
                                    print(f"Unable to upload the xclbin for {hwdb}")
                                else:
                                    link = f"https://{ci_env['AWS_BUCKET_NAME']}.s3.{ci_env['AWS_DEFAULT_REGION']}.amazonaws.com/{file_name}"
                                    print(f"Uploaded xclbin for {hwdb} to {link}")
                                    hwdb_to_link[hwdb] = f"https://{ci_env['AWS_BUCKET_NAME']}.s3.{ci_env['AWS_DEFAULT_REGION']}.amazonaws.com/{file_name}"

                # parse the output yamls, replace the sample hwdb's xclbin line only
                sample_hwdb_filename = f"{manager_fsim_dir}/deploy/sample-backup-configs/sample_config_hwdb.yaml"
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

if __name__ == "__main__":
    execute(run_xclbin_buildbitstream, hosts=["localhost"])
