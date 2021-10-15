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
    """ Must use local here. determine where the firesim/deploy dir is """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

def replace_rtl(build_config):
    """ Generate Verilog """
    rootLogger.info("Building Verilog for {}".format(str(build_config.get_chisel_triplet())))
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run("{}/general-scripts/replace-rtl.sh {} {} {} {} \"{}\"".format(
            get_deploy_dir() + "/buildtools",
            os.getenv('RISCV', ""),
            os.getenv('PATH', ""),
            os.getenv('LD_LIBRARY_PATH', ""),
            get_deploy_dir() + "/..",
            build_config.make_recipe("PLATFORM=f1 replace-rtl")))

def build_driver(build_config):
    """ Build FPGA driver """
    rootLogger.info("Building FPGA driver for {}".format(str(build_config.get_chisel_triplet())))
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run("{}/general-scripts/build-driver.sh {} {} {} {} \"{}\"".format(
            get_deploy_dir() + "/buildtools",
            os.getenv('RISCV', ""),
            os.getenv('PATH', ""),
            os.getenv('LD_LIBRARY_PATH', ""),
            get_deploy_dir() + "/..",
            build_config.make_recipe("PLATFORM=f1 driver")))

def pre_remote_build(build_config):
    # First, Produce dcp/tar for design. Runs on remote machines, out of
    # $HOME/firesim-build/ """

    fpga_build_dir = "hdk/cl/developer_designs/cl_{}".format(build_config.get_chisel_triplet())
    local_deploy_dir = get_deploy_dir()

    # local paths
    local_fsim_dir = "{}/..".format(local_deploy_dir)
    local_awsfpga_dir = "{}/platforms/f1/aws-fpga".format(local_fsim_dir)

    # remote paths
    remote_home_dir = ""
    with StreamLogger('stdout'), StreamLogger('stderr'):
        remote_home_dir = run('echo $HOME')

    # override if build farm dispatcher asked for it
    if build_config.build_farm_dispatcher.override_remote_build_dir:
        remote_home_dir = build_config.build_farm_dispatcher.override_remote_build_dir

    remote_build_dir = "{}/firesim-build".format(remote_home_dir)
    f1_platform_dir = "{}/platforms/f1/".format(remote_build_dir)
    awsfpga_dir = "{}/aws-fpga".format(f1_platform_dir)

    # copy aws-fpga to the build instance.
    # do the rsync, but ignore any checkpoints that might exist on this machine
    # (in case builds were run locally)
    # extra_opts -l preserves symlinks
    with StreamLogger('stdout'), StreamLogger('stderr'):
        run('mkdir -p {}'.format(f1_platform_dir))
        rsync_cap = rsync_project(
            local_dir=local_awsfpga_dir,
            remote_dir=f1_platform_dir,
            ssh_opts="-o StrictHostKeyChecking=no",
            exclude="hdk/cl/developer_designs/cl_*",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)
        rsync_cap = rsync_project(
            local_dir="{}/{}/*".format(local_awsfpga_dir, fpga_build_dir),
            remote_dir='{}/{}'.format(awsfpga_dir, fpga_build_dir),
            exclude='build/checkpoints',
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    return "{}/{}".format(awsfpga_dir, fpga_build_dir)

@parallel
def aws_build(global_build_config, bypass=False):
    """ Run Vivado, convert tar -> AGFI/AFI. Then terminate the instance at the end.
    conf = build_config dicitonary
    bypass: since this function takes a long time, bypass just returns for
    testing purposes when set to True. """

    build_config = global_build_config.get_build_by_ip(env.host_string)
    if bypass:
        build_config.build_farm_dispatcher.terminate_build_instance()
        return

    # The default error-handling procedure. Send an email and teardown instance
    def on_build_failure():
        message_title = "FireSim FPGA Build Failed"

        message_body = "Your FPGA build failed for triplet: " + build_config.get_chisel_triplet()

        send_firesim_notification(message_title, message_body)

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        build_config.build_farm_dispatcher.terminate_build_instance()

    rootLogger.info("Building AWS F1 AGFI from Verilog")

    # local AWS build directory; might have config-specific changes to fpga flow
    fpga_build_dir = "hdk/cl/developer_designs/cl_{}".format(build_config.get_chisel_triplet())
    results_dir = build_config.get_build_dir_name()

    # cl_dir is the cl_dir that is either local or remote
    # if locally no need to copy things around (the makefile should have already created a CL_DIR w. the tuple
    # if remote (aka not locally) then you need to copy things
    local_deploy_dir = get_deploy_dir()
    cl_dir = ""

    if build_config.build_farm_dispatcher.is_local:
        cl_dir = "{}/../platforms/f1/aws-fpga/{}".format(local_deploy_dir, fpga_build_dir)
    else:
        cl_dir = pre_remote_build(build_config)

    vivado_result = 0
    with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        # copy script to the cl_dir and execute
        rsync_cap = rsync_project(
            local_dir="{}/buildtools/platform-specific-scripts/f1/build-bitstream.sh".format(local_deploy_dir),
            remote_dir="{}/".format(cl_dir),
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

        vivado_result = run("{}/build-bitstream.sh {}".format(cl_dir, cl_dir)).return_code

    # put build results in the result-build area
    with StreamLogger('stdout'), StreamLogger('stderr'):
        rsync_cap = rsync_project(
            local_dir="""{}/results-build/{}/""".format(local_deploy_dir, results_dir),
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

    build_config.build_farm_dispatcher.terminate_build_instance()

def aws_create_afi(build_config):
    """
    Convert the tarball created by Vivado build into an Amazon Global FPGA Image (AGFI)

    :return: None on error
    """
    ## next, do tar -> AGFI
    ## This is done on the local copy

    local_deploy_dir = get_deploy_dir()
    results_dir = build_config.get_build_dir_name()

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

    with lcd("""{}/results-build/{}/cl_firesim/build/checkpoints/to_aws/""".format(local_deploy_dir, results_dir)), StreamLogger('stdout'), StreamLogger('stderr'):
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
    results_build_dir = """{}/results-build/{}/""".format(local_deploy_dir, results_dir)
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
        agfi_entry += "customruntimeconfig=None\n\n"
        message_body = "Your AGFI has been created!\nAdd\n" + agfi_entry + "\nto your config_hwdb.ini to use this hardware configuration."

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
