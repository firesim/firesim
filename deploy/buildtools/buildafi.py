from __future__ import with_statement
import json
import time
import random
import string
import logging
import os
from fabric.api import prefix, local, run, env, lcd, parallel # type: ignore
from fabric.contrib.console import confirm  # type: ignore
from fabric.contrib.project import rsync_project # type: ignore

from awstools.afitools import *
from awstools.awstools import send_firesim_notification
from util.streamlogger import StreamLogger, InfoStreamLogger

# imports needed for python type checking
from typing import Optional
from buildtools.buildconfig import BuildConfig
from buildtools.buildconfigfile import BuildConfigFile

rootLogger = logging.getLogger()

def get_deploy_dir() -> str:
    """Determine where the firesim/deploy directory is and return its path.

    Returns:
        Path to firesim/deploy directory.
    """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

def replace_rtl(build_config: BuildConfig) -> None:
    """Generate Verilog from build config.

    Args:
        build_config: Build configuration to make Verilog from.
    """
    rootLogger.info(f"Building Verilog for {build_config.get_chisel_triplet()}")

    with prefix(f'cd {get_deploy_dir()}/../'), \
         prefix(f"""export RISCV={os.getenv('RISCV', "")}"""), \
         prefix(f"""export PATH={os.getenv('PATH', "")}"""), \
         prefix(f"""export LD_LIBRARY_PATH={os.getenv('LD_LIBRARY_PATH', "")}"""), \
         prefix('source sourceme-f1-manager.sh'), \
         prefix('cd sim/'), \
         InfoStreamLogger('stdout'), \
         InfoStreamLogger('stderr'):
        run(build_config.make_recipe("PLATFORM=f1 replace-rtl"))

def build_driver(build_config: BuildConfig) -> None:
    """Build FireSim FPGA driver from build config.

    Args:
        build_config: Build configuration to make driver from.
    """
    rootLogger.info(f"Building FPGA driver for {build_config.get_chisel_triplet()}")

    with prefix(f'cd {get_deploy_dir()}/../'), \
         prefix(f"""export RISCV={os.getenv('RISCV', "")}"""), \
         prefix(f"""export PATH={os.getenv('PATH', "")}"""), \
         prefix(f"""export LD_LIBRARY_PATH={os.getenv('LD_LIBRARY_PATH', "")}"""), \
         prefix('source sourceme-f1-manager.sh'), \
         prefix('cd sim/'), \
         InfoStreamLogger('stdout'), \
         InfoStreamLogger('stderr'):
        run(build_config.make_recipe("PLATFORM=f1 driver"))

def cl_dir_setup(chisel_triplet: str, dest_build_dir: str) -> str:
    """Setup CL_DIR on build host.

    Args:
        chisel_triplet: Build config chisel triplet used to uniquely identify build dir.
        dest_build_dir: Destination base directory to use.

    Returns:
        Path to CL_DIR directory (that is setup) or `None` if invalid.
    """
    fpga_build_postfix = f"hdk/cl/developer_designs/cl_{chisel_triplet}"

    # local paths
    local_awsfpga_dir = f"{get_deploy_dir()}/../platforms/f1/aws-fpga"

    dest_f1_platform_dir = f"{dest_build_dir}/platforms/f1/"
    dest_awsfpga_dir = f"{dest_f1_platform_dir}/aws-fpga"

    # copy aws-fpga to the build instance.
    # do the rsync, but ignore any checkpoints that might exist on this machine
    # (in case builds were run locally)
    # extra_opts -l preserves symlinks
    with StreamLogger('stdout'), StreamLogger('stderr'):
        run(f'mkdir -p {dest_f1_platform_dir}')
        rsync_cap = rsync_project(
            local_dir=local_awsfpga_dir,
            remote_dir=dest_f1_platform_dir,
            ssh_opts="-o StrictHostKeyChecking=no",
            exclude=["hdk/cl/developer_designs/cl_*"],
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)
        rsync_cap = rsync_project(
            local_dir=f"{local_awsfpga_dir}/{fpga_build_postfix}/*",
            remote_dir=f'{dest_awsfpga_dir}/{fpga_build_postfix}',
            exclude=["build/checkpoints"],
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    return f"{dest_awsfpga_dir}/{fpga_build_postfix}"

@parallel
def aws_build(build_config_file: BuildConfigFile, bypassAll: bool = False, bypassVivado: bool = False) -> None:
    """Run Vivado, convert tar into AGFI/AFI. Terminate the instance at the end.
    Must run after replace_rtl and build_driver are run.

    Args:
        build_config_file: Global build file.
        bypassAll: If true, immediately return and terminate build host. Used for testing purposes.
        bypassVivado: If true, execute the function up until Vivado is called, then terminate build host. Used for testing purposes.
    """
    build_config = build_config_file.get_build_by_ip(env.host_string)

    if not build_config:
        rootLogger.info(f"Failed to find build config for IP address: {env.host_string}. Unable to release build host.")
        return

    if bypassAll:
        build_config_file.build_farm.release_build_host(build_config)
        return

    # The default error-handling procedure. Send an email and teardown instance
    def on_build_failure():
        """Terminate build host and notify user that build failed."""

        message_title = "FireSim FPGA Build Failed"

        message_body = "Your FPGA build failed for triplet: " + build_config.get_chisel_triplet()
        message_body += ".\nInspect the log output from IP address " + env.host_string + " for more information."

        send_firesim_notification(message_title, message_body)

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        build_config_file.build_farm.release_build_host(build_config)

    rootLogger.info("Building AWS F1 AGFI from Verilog")

    local_deploy_dir = get_deploy_dir()
    fpga_build_postfix = f"hdk/cl/developer_designs/cl_{build_config.get_chisel_triplet()}"
    local_results_dir = f"{local_deploy_dir}/results-build/{build_config.get_build_dir_name()}"

    # 'cl_dir' holds the eventual directory in which vivado will run.
    cl_dir = cl_dir_setup(build_config.get_chisel_triplet(), build_config_file.build_farm.get_build_host(build_config).dest_build_dir)

    if bypassVivado:
        build_config_file.build_farm.release_build_host(build_config)
        return

    vivado_result = 0
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        # copy script to the cl_dir and execute
        rsync_cap = rsync_project(
            local_dir=f"{local_deploy_dir}/../platforms/f1/build-bitstream.sh",
            remote_dir=f"{cl_dir}/",
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

        vivado_result = run(f"{cl_dir}/build-bitstream.sh {cl_dir}").return_code

    # put build results in the result-build area
    with StreamLogger('stdout'), StreamLogger('stderr'):
        rsync_cap = rsync_project(
            local_dir=f"{local_results_dir}/",
            remote_dir=cl_dir,
            ssh_opts="-o StrictHostKeyChecking=no", upload=False, extra_opts="-l",
            capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    if vivado_result != 0:
        on_build_failure()
        return

    if not aws_create_afi(build_config):
        on_build_failure()
        return

    build_config_file.build_farm.release_build_host(build_config)

def aws_create_afi(build_config: BuildConfig) -> Optional[bool]:
    """Convert the tarball created by Vivado build into an Amazon Global FPGA Image (AGFI).

    Args:
        build_config: Build config to determine paths.

    Returns:
        `True` on success, `None` on error.
    """
    local_deploy_dir = get_deploy_dir()
    local_results_dir = f"{local_deploy_dir}/results-build/{build_config.get_build_dir_name()}"

    afi = None
    agfi = None
    s3bucket = build_config.s3_bucketname
    afiname = build_config.name

    # construct the "tags" we store in the AGFI description
    tag_buildtriplet = build_config.get_chisel_triplet()
    tag_deploytriplet = tag_buildtriplet
    if build_config.deploytriplet:
        tag_deploytriplet = build_config.deploytriplet

    # the asserts are left over from when we tried to do this with tags
    # - technically I don't know how long these descriptions are allowed to be,
    # but it's at least 256*3, so I'll leave these here for now as sanity
    # checks.
    assert len(tag_buildtriplet) <= 255, "ERR: aws does not support tags longer than 256 chars for buildtriplet"
    assert len(tag_deploytriplet) <= 255, "ERR: aws does not support tags longer than 256 chars for deploytriplet"

    with StreamLogger('stdout'), StreamLogger('stderr'):
        is_dirty_str = local("if [[ $(git status --porcelain) ]]; then echo '-dirty'; fi", capture=True)
        hash = local("git rev-parse HEAD", capture=True)
    tag_fsimcommit = hash + is_dirty_str

    assert len(tag_fsimcommit) <= 255, "ERR: aws does not support tags longer than 256 chars for fsimcommit"

    # construct the serialized description from these tags.
    description = firesim_tags_to_description(tag_buildtriplet, tag_deploytriplet, tag_fsimcommit)

    # if we're unlucky, multiple vivado builds may launch at the same time. so we
    # append the build node IP + a random string to diff them in s3
    global_append = "-" + str(env.host_string) + "-" + ''.join(random.SystemRandom().choice(string.ascii_uppercase + string.digits) for _ in range(10)) + ".tar"

    with lcd(f"{local_results_dir}/cl_{tag_buildtriplet}/build/checkpoints/to_aws/"), StreamLogger('stdout'), StreamLogger('stderr'):
        files = local('ls *.tar', capture=True)
        rootLogger.debug(files)
        rootLogger.debug(files.stderr)
        tarfile = files.split()[-1]
        s3_tarfile = tarfile + global_append
        localcap = local('aws s3 cp ' + tarfile + ' s3://' + s3bucket + '/dcp/' + s3_tarfile, capture=True)
        rootLogger.debug(localcap)
        rootLogger.debug(localcap.stderr)
        agfi_afi_ids = local(f"""aws ec2 create-fpga-image --input-storage-location Bucket={s3bucket},Key={"dcp/" + s3_tarfile} --logs-storage-location Bucket={s3bucket},Key={"logs/"} --name "{afiname}" --description "{description}" """, capture=True)
        rootLogger.debug(agfi_afi_ids)
        rootLogger.debug(agfi_afi_ids.stderr)
        rootLogger.debug("create-fpge-image result: " + str(agfi_afi_ids))
        ids_as_dict = json.loads(agfi_afi_ids)
        agfi = ids_as_dict["FpgaImageGlobalId"]
        afi = ids_as_dict["FpgaImageId"]
        rootLogger.info("Resulting AGFI: " + str(agfi))
        rootLogger.info("Resulting AFI: " + str(afi))

    rootLogger.info("Waiting for create-fpga-image completion.")
    checkstate = "pending"
    with lcd(local_results_dir), StreamLogger('stdout'), StreamLogger('stderr'):
        while checkstate == "pending":
            imagestate = local(f"aws ec2 describe-fpga-images --fpga-image-id {afi} | tee AGFI_INFO", capture=True)
            state_as_dict = json.loads(imagestate)
            checkstate = state_as_dict["FpgaImages"][0]["State"]["Code"]
            rootLogger.info("Current state: " + str(checkstate))
            time.sleep(10)


    if checkstate == "available":
        # copy the image to all regions for the current user
        copy_afi_to_all_regions(afi)

        message_title = "FireSim FPGA Build Completed"
        agfi_entry = afiname + ":\n"
        agfi_entry += "    afgi: " + agfi + "\n"
        agfi_entry += "    deploy-triplet-override: null\n"
        agfi_entry += "    custom-runtime-config: null\n"
        message_body = "Your AGFI has been created!\nAdd\n\n" + agfi_entry + "\nto your config_hwdb.yaml to use this hardware configuration."

        send_firesim_notification(message_title, message_body)

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        # for convenience when generating a bunch of images. you can just
        # cat all the files in this directory after your builds finish to get
        # all the entries to copy into config_hwdb.yaml
        hwdb_entry_file_location = f"{local_deploy_dir}/built-hwdb-entries/"
        local("mkdir -p " + hwdb_entry_file_location)
        with open(hwdb_entry_file_location + "/" + afiname, "w") as outputfile:
            outputfile.write(agfi_entry)

        if build_config.post_build_hook:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                localcap = local(f"{build_config.post_build_hook} {local_results_dir}", capture=True)
                rootLogger.debug("[localhost] " + str(localcap))
                rootLogger.debug("[localhost] " + str(localcap.stderr))

        rootLogger.info(f"Build complete! AFI ready. See {os.path.join(hwdb_entry_file_location,afiname)}.")
        return True
    else:
        return None
