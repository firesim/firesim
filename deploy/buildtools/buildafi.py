from __future__ import with_statement
import json
import time
import random
import string
import logging

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

def replace_rtl(conf, buildconfig):
    """ Run chisel/firrtl/fame-1, produce verilog for fpga build.

    THIS ALWAYS RUNS LOCALLY"""
    builddir = buildconfig.get_build_dir_name()
    fpgabuilddir = "hdk/cl/developer_designs/cl_" + buildconfig.get_chisel_triplet()
    ddir = get_deploy_dir()

    rootLogger.info("Running replace-rtl to generate verilog for " + str(buildconfig.get_chisel_triplet()))

    with prefix('cd ' + ddir + '/../'), prefix('source sourceme-f1-manager.sh'), prefix('export CL_DIR={}/../platforms/f1/aws-fpga/{}'.format(ddir, fpgabuilddir)), prefix('cd sim/'), StreamLogger('stdout'), StreamLogger('stderr'):
        run(buildconfig.make_recipe("replace-rtl"))
        run("""mkdir -p {}/results-build/{}/""".format(ddir, builddir))
        run("""cp $CL_DIR/design/cl_firesim_generated.sv {}/results-build/{}/cl_firesim_generated.sv""".format(ddir, builddir))

    # build the fpga driver that corresponds with this version of the RTL
    with prefix('cd ' + ddir + '/../'), prefix('source sourceme-f1-manager.sh'), prefix('cd sim/'), StreamLogger('stdout'), StreamLogger('stderr'):
        run(buildconfig.make_recipe("f1"))

@parallel
def aws_build(global_build_config, bypass=False):
    """ Run Vivado, convert tar -> AGFI/AFI. Then terminate the instance at the end.
    conf = buildconfig dicitonary
    bypass: since this function takes a long time, bypass just returns for
    testing purposes when set to True. """
    if bypass:
        ### This is duplicated from the end of the function.
        buildconfig = global_build_config.get_build_by_ip(env.host_string)
        buildconfig.terminate_build_instance(buildconfig)
        return

    rootLogger.info("Running process to build AGFI from verilog.")

    # First, Produce dcp/tar for design. Runs on remote machines, out of
    # /home/centos/firesim-build/ """
    ddir = get_deploy_dir()
    buildconfig = global_build_config.get_build_by_ip(env.host_string)
    builddir = buildconfig.get_build_dir_name()
    # local AWS build directory; might have config-specific changes to fpga flow
    fpgabuilddir = "hdk/cl/developer_designs/cl_" + buildconfig.get_chisel_triplet()
    remotefpgabuilddir = "hdk/cl/developer_designs/cl_firesim"

    # first, copy aws-fpga to the build instance. it will live in
    # firesim-build/platforms/f1/
    with StreamLogger('stdout'), StreamLogger('stderr'):
        run('mkdir -p /home/centos/firesim-build/platforms/f1/')
    # do the rsync, but ignore any checkpoints that might exist on this machine
    # (in case builds were run locally)
    # extra_opts -l preserves symlinks
    with StreamLogger('stdout'), StreamLogger('stderr'):
        rsync_cap = rsync_project(local_dir=ddir + "/../platforms/f1/aws-fpga",
                      remote_dir='/home/centos/firesim-build/platforms/f1/',
                      ssh_opts="-o StrictHostKeyChecking=no",
                      exclude="hdk/cl/developer_designs/cl_*",
                      extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)
        rsync_cap = rsync_project(local_dir=ddir + "/../platforms/f1/aws-fpga/{}/*".format(fpgabuilddir),
                      remote_dir='/home/centos/firesim-build/platforms/f1/aws-fpga/' + remotefpgabuilddir,
                      exclude='build/checkpoints',
                      ssh_opts="-o StrictHostKeyChecking=no",
                      extra_opts="-l", capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    # run the Vivado build
    with prefix('cd /home/centos/firesim-build/platforms/f1/aws-fpga'), \
         prefix('source hdk_setup.sh'), \
         prefix('export CL_DIR=/home/centos/firesim-build/platforms/f1/aws-fpga/' + remotefpgabuilddir), \
         prefix('cd $CL_DIR/build/scripts/'), InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
        run('./aws_build_dcp_from_cl.sh -foreground')

    # rsync in the reverse direction to get build results
    with StreamLogger('stdout'), StreamLogger('stderr'):
        rsync_cap = rsync_project(local_dir="""{}/results-build/{}/""".format(ddir, builddir),
                      remote_dir='/home/centos/firesim-build/platforms/f1/aws-fpga/' + remotefpgabuilddir,
                      ssh_opts="-o StrictHostKeyChecking=no", upload=False, extra_opts="-l",
                      capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

    ## next, do tar -> AGFI
    ## This is done on the local copy

    afi = None
    agfi = None
    s3bucket = global_build_config.s3_bucketname
    afiname = buildconfig.name

    # construct the "tags" we store in the AGFI description
    tag_buildtriplet = buildconfig.get_chisel_triplet()
    tag_deploytriplet = tag_buildtriplet
    if buildconfig.deploytriplet != "None":
        tag_deploytriplet = buildconfig.deploytriplet

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
    global_append = "-" + env.host_string + "-" + ''.join(random.SystemRandom().choice(string.ascii_uppercase + string.digits) for _ in range(10)) + ".tar"

    with lcd("""{}/results-build/{}/cl_firesim/build/checkpoints/to_aws/""".format(ddir, builddir)), StreamLogger('stdout'), StreamLogger('stderr'):
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
    results_build_dir = """{}/results-build/{}/""".format(ddir, builddir)
    with lcd(results_build_dir), StreamLogger('stdout'), StreamLogger('stderr'):
        checkstate = "pending"
        while checkstate == "pending":
            imagestate = local("""aws ec2 describe-fpga-images --fpga-image-id {} | tee AGFI_INFO""".format(afi), capture=True)
            state_as_dict = json.loads(imagestate)
            checkstate = state_as_dict["FpgaImages"][0]["State"]["Code"]
            rootLogger.info("Current state: " + str(checkstate))
            time.sleep(10)

    # copy the image to all regions for the current user
    copy_afi_to_all_regions(afi)

    message_title = "FireSim FPGA Build Completed"
    agfi_entry = "[" + afiname + "]\nagfi=" + agfi + "\ndeploytripletoverride=None\ncustomruntimeconfig=None\n\n"
    message_body = "Your AGFI has been created!\nAdd\n" + agfi_entry + "\nto your config_hwdb.ini to use this hardware configuration."

    send_firesim_notification(message_title, message_body)

    rootLogger.info(message_title)
    rootLogger.info(message_body)

    # for convenience when generating a bunch of images. you can just
    # cat all the files in this directory after your builds finish to get
    # all the entries to copy into config_hwdb.ini
    hwdb_entry_file_location = """{}/built-hwdb-entries/""".format(ddir)
    local("mkdir -p " + hwdb_entry_file_location)
    with open(hwdb_entry_file_location + "/" + afiname, "w") as outputfile:
        outputfile.write(agfi_entry)

    if global_build_config.post_build_hook:
        with StreamLogger('stdout'), StreamLogger('stderr'):
            localcap = local("""{} {}""".format(global_build_config.post_build_hook,
                                                results_build_dir,
                                                capture=True))
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

    rootLogger.info("Build complete! AFI ready. See AGFI_INFO.")
    rootLogger.info("Terminating the build instance now.")
    buildconfig.terminate_build_instance()
