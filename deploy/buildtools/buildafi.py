from __future__ import with_statement
import json
import time
import random
import string
import logging
import os

from fabric.api import *
from fabric.contrib.console import confirm
from fabric.contrib.project import rsync_project
from awstools.afitools import *
from awstools.awstools import send_firesim_notification
from util.streamlogger import StreamLogger, InfoStreamLogger

rootLogger = logging.getLogger()

def get_deploy_dir():
    """ Determine where the firesim/deploy directory is and return its path.

    Returns:
        (str): Path to firesim/deploy directory
    """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

def replace_rtl(build_config):
    """ Generate Verilog from build config

    Parameters:
        build_config (BuildConfig): Build configuration to make Verilog from
    """
    rootLogger.info("Building Verilog for {}".format(str(build_config.get_chisel_triplet())))

    with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
         prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
         prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
         prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
         prefix('source sourceme-f1-manager.sh'), \
         prefix('cd sim/'), \
         InfoStreamLogger('stdout'), \
         InfoStreamLogger('stderr'):
        run(build_config.make_recipe("PLATFORM=f1 replace-rtl"))

def build_driver(build_config):
    """ Build FireSim FPGA driver from build config

    Parameters:
        build_config (BuildConfig): Build configuration to make driver from
    """
    rootLogger.info("Building FPGA driver for {}".format(str(build_config.get_chisel_triplet())))

    with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
         prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
         prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
         prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
         prefix('source sourceme-f1-manager.sh'), \
         prefix('cd sim/'), \
         InfoStreamLogger('stdout'), \
         InfoStreamLogger('stderr'):
        run(buildconfig.make_recipe("PLATFORM=f1 driver"))

def remote_setup(build_config):
    """ Setup CL_DIR on remote machine

    Parameters:
        build_config (BuildConfig): Build configuration to determine paths
    Returns:
        (str): Path to remote CL_DIR directory (that is setup)
    """

    fpga_build_postfix = "hdk/cl/developer_designs/cl_{}".format(build_config.get_chisel_triplet())

    # local paths
    local_awsfpga_dir = "{}/../platforms/f1/aws-fpga".format(get_deploy_dir())

    # remote paths
    remote_home_dir = ""
    with StreamLogger('stdout'), StreamLogger('stderr'):
        remote_home_dir = run('echo $HOME')

    # potentially override build dir
    if build_config.build_host_dispatcher.override_remote_build_dir:
        remote_home_dir = build_config.build_host_dispatcher.override_remote_build_dir

    remote_build_dir = "{}/firesim-build".format(remote_home_dir)
    remote_f1_platform_dir = "{}/platforms/f1/".format(remote_build_dir)
    remote_awsfpga_dir = "{}/aws-fpga".format(remote_f1_platform_dir)

    # copy aws-fpga to the build instance.
    # do the rsync, but ignore any checkpoints that might exist on this machine
    # (in case builds were run locally)
    # extra_opts -l preserves symlinks
    with StreamLogger('stdout'), StreamLogger('stderr'):
        run('mkdir -p {}'.format(remote_f1_platform_dir))
        rsync_cap = rsync_project(
            local_dir=local_awsfpga_dir,
            remote_dir=remote_f1_platform_dir,
            ssh_opts="-o StrictHostKeyChecking=no",
            exclude="hdk/cl/developer_designs/cl_*",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)
        rsync_cap = rsync_project(
            local_dir="{}/{}/*".format(local_awsfpga_dir, fpga_build_postfix),
            remote_dir='{}/{}'.format(remote_awsfpga_dir, fpga_build_postfix),
            exclude='build/checkpoints',
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    return "{}/{}".format(remote_awsfpga_dir, fpga_build_postfix)

@parallel
def aws_build(global_build_config, bypass=False):
    """ Run Vivado, convert tar into AGFI/AFI. Terminate the instance at the end.
    Must run after replace_rtl and build_driver are run.

    Parameters:
        global_build_config (BuildConfigFile): Global build file
        bypass (bool): If true, immediately return and terminate instance. Used for testing purposes
    """

    build_config = global_build_config.get_build_by_ip(env.host_string)
    if bypass:
        build_config.build_host_dispatcher.release_build_host()
        return

    # The default error-handling procedure. Send an email and teardown instance
    def on_build_failure():
        """ Terminate build host and notify user that build failed """

        message_title = "FireSim FPGA Build Failed"

        message_body = "Your FPGA build failed for triplet: " + build_config.get_chisel_triplet()

        send_firesim_notification(message_title, message_body)

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        build_config.build_host_dispatcher.release_build_host()

    rootLogger.info("Building AWS F1 AGFI from Verilog")

    local_deploy_dir = get_deploy_dir()
    fpga_build_postfix = "hdk/cl/developer_designs/cl_{}".format(build_config.get_chisel_triplet())
    local_results_dir = "{}/results-build/{}".format(local_deploy_dir, build_config.get_build_dir_name())

    # cl_dir is the cl_dir that is either local or remote
    # if locally no need to copy things around (the makefile should have already created a CL_DIR w. the tuple)
    # if remote (aka not locally) then you need to copy things
    cl_dir = ""
    local_cl_dir = "{}/../platforms/f1/aws-fpga/{}".format(local_deploy_dir, fpga_build_postfix)

    # copy over generated RTL into local CL_DIR before remote
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run("""mkdir -p {}""".format(local_results_dir))
        run("""cp {}/design/FireSim-generated.sv {}/FireSim-generated.sv""".format(local_cl_dir, local_results_dir))

    if build_config.build_host_dispatcher.is_local:
        cl_dir = local_cl_dir
    else:
        cl_dir = remote_setup(build_config)

    vivado_result = 0
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        # copy script to the cl_dir and execute
        rsync_cap = rsync_project(
            local_dir="{}/../platforms/f1/build-bitstream.sh".format(local_deploy_dir),
            remote_dir="{}/".format(cl_dir),
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

        vivado_result = run("{}/build-bitstream.sh {}".format(cl_dir, cl_dir)).return_code

    # put build results in the result-build area
    with StreamLogger('stdout'), StreamLogger('stderr'):
        rsync_cap = rsync_project(
            local_dir="{}/".format(local_results_dir),
            remote_dir="{}".format(cl_dir),
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

    build_config.build_host_dispatcher.release_build_host()

def aws_create_afi(build_config):
    """
    Convert the tarball created by Vivado build into an Amazon Global FPGA Image (AGFI)

    Parameters:
        build_config (BuildConfig): Build config to determine paths
    Returns:
        (bool or None): True on success, None on error
    """

    local_deploy_dir = get_deploy_dir()
    local_results_dir = "{}/results-build/{}".format(local_deploy_dir, build_config.get_build_dir_name())

    afi = None
    agfi = None
    s3bucket = build_config.s3_bucketname
    afiname = build_config.name

    # construct the "tags" we store in the AGFI description
    tag_buildtriplet = build_config.get_chisel_triplet()
    tag_deploytriplet = tag_buildtriplet
    if build_config.deploytriplet != "None":
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

    with lcd("""{}/cl_{}/build/checkpoints/to_aws/""".format(local_results_dir, tag_buildtriplet)), StreamLogger('stdout'), StreamLogger('stderr'):
        files = local('ls *.tar', capture=True)
        rootLogger.debug(files)
        rootLogger.debug(files.stderr)
        tarfile = files.split()[-1]
        s3_tarfile = tarfile + global_append
        localcap = local('aws s3 cp ' + tarfile + ' s3://' + s3bucket + '/dcp/' + s3_tarfile, capture=True)
        rootLogger.debug(localcap)
        rootLogger.debug(localcap.stderr)
        agfi_afi_ids = local("""aws ec2 create-fpga-image --input-storage-location Bucket={},Key={} --logs-storage-location Bucket={},Key={} --name "{}" --description "{}" """.format(s3bucket, "dcp/" + s3_tarfile, s3bucket, "logs/", afiname, description), capture=True)
        rootLogger.debug(agfi_afi_ids)
        rootLogger.debug(agfi_afi_ids.stderr)
        rootLogger.debug("create-fpge-image result: " + str(agfi_afi_ids))
        ids_as_dict = json.loads(agfi_afi_ids)
        agfi = ids_as_dict["FpgaImageGlobalId"]
        afi = ids_as_dict["FpgaImageId"]
        rootLogger.info("Resulting AGFI: " + str(agfi))
        rootLogger.info("Resulting AFI: " + str(afi))

    rootLogger.info("Waiting for create-fpga-image completion.")
    results_build_dir = """{}/""".format(local_results_dir)
    checkstate = "pending"
    with lcd(results_build_dir), StreamLogger('stdout'), StreamLogger('stderr'):
        while checkstate == "pending":
            imagestate = local("""aws ec2 describe-fpga-images --fpga-image-id {} | tee AGFI_INFO""".format(afi), capture=True)
            state_as_dict = json.loads(imagestate)
            checkstate = state_as_dict["FpgaImages"][0]["State"]["Code"]
            rootLogger.info("Current state: " + str(checkstate))
            time.sleep(10)


    if checkstate == "available":
        # copy the image to all regions for the current user
        copy_afi_to_all_regions(afi)

        message_title = "FireSim FPGA Build Completed"
        agfi_entry = "[" + afiname + "]\n"
        agfi_entry += "afgi=" + agfi + "\n"
        agfi_entry += "deploytripletoverride=None\n"
        agfi_entry += "customruntimeconfig=None\n"
        message_body = "Your AGFI has been created!\nAdd\n\n" + agfi_entry + "\nto your config_hwdb.ini to use this hardware configuration."

        send_firesim_notification(message_title, message_body)

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        # for convenience when generating a bunch of images. you can just
        # cat all the files in this directory after your builds finish to get
        # all the entries to copy into config_hwdb.ini
        hwdb_entry_file_location = """{}/built-hwdb-entries/""".format(local_deploy_dir)
        local("mkdir -p " + hwdb_entry_file_location)
        with open(hwdb_entry_file_location + "/" + afiname, "w") as outputfile:
            outputfile.write(agfi_entry)

        if build_config.post_build_hook:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                localcap = local("""{} {}""".format(build_config.post_build_hook,
                                                    results_build_dir,
                                                    capture=True))
                rootLogger.debug("[localhost] " + str(localcap))
                rootLogger.debug("[localhost] " + str(localcap.stderr))

        rootLogger.info("Build complete! AFI ready. See {}.".format(os.path.join(hwdb_entry_file_location,afiname)))
        return True
    else:
        return
