from __future__ import with_statement
import json
import time
import random
import string
import logging
import os

from fabric.contrib.project import rsync_project
from awstools.afitools import *
from awstools.awstools import send_firesim_notification
from util.streamlogger import StreamLogger, InfoStreamLogger

rootLogger = logging.getLogger()

class GlobalAwsBuildConfig:
    def __init__(self, args, global_build_configfile):
        # parse afibuild section
        self.s3_bucketname = \
            global_build_configfile.get('afibuild', 's3bucketname')

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['s3bucketname'] is not None:
            # in tutorial mode, special s3 bucket name
            self.s3_bucketname = aws_resource_names_dict['s3bucketname']

        self.build_instance_market = \
                global_build_configfile.get('afibuild', 'buildinstancemarket')
        self.spot_interruption_behavior = \
            global_build_configfile.get('afibuild', 'spotinterruptionbehavior')
        self.spot_max_price = \
                    global_build_configfile.get('afibuild', 'spotmaxprice')
        self.post_build_hook = global_build_configfile.get('afibuild', 'postbuildhook')

        # parse agfistoshare
        self.agfistoshare = [x[0] for x in global_build_configfile.items('agfistoshare')]

        # parse sharewithaccounts
        self.acctids_to_sharewith = [x[1] for x in global_build_configfile.items('sharewithaccounts')]

    def launch_build_instances(self):
        # get access to the runfarmprefix, which we will apply to build
        # instances too now.
        aws_resource_names_dict = aws_resource_names()
        # just duplicate the runfarmprefix for now. This can be None,
        # in which case we give an empty build farm prefix
        build_farm_prefix = aws_resource_names_dict['runfarmprefix']

        for build in self.builds_list:
            build.launch_build_instance(self.build_instance_market,
                                        self.spot_interruption_behavior,
                                        self.spot_max_price,
                                        build_farm_prefix)

    def wait_build_instances(self):
        instances = [build.get_launched_instance_object() for build in self.builds_list]
        wait_on_instance_launches(instances)

    def terminate_all_build_instances(self):
        for build in self.builds_list:
            build.terminate_build_instance()

    def host_platform_init(self):
        auto_create_bucket(self.s3_bucketname)

    def replace_rtl(self, buildconf):
        """ Run chisel/firrtl/fame-1, produce verilog for fpga build.
        THIS ALWAYS RUNS LOCALLY."""

        builddir = buildconf.get_build_dir_name()
        ddir = self.get_deploy_dir()
        fpgabuilddir = "hdk/cl/developer_designs/cl_" + buildconf.get_chisel_triplet()

        with prefix('cd ' + ddir + '/../'), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('export CL_DIR={}/../platforms/f1/aws-fpga/{}'.format(ddir, fpgabuilddir)), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(buildconf.make_recipe("replace-rtl"))
            run("""mkdir -p {}/results-build/{}/""".format(ddir, builddir))
            run("""cp $CL_DIR/design/cl_firesim_generated.sv {}/results-build/{}/cl_firesim_generated.sv""".format(ddir, builddir))

        # build the fpga driver that corresponds with this version of the RTL
        with prefix('cd ' + ddir + '/../'), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            StreamLogger('stdout'), \
            StreamLogger('stderr'):
            run(buildconf.make_recipe("f1"))

    def fpga_build(self, global_build_config, bypass=False):
        """ Run Vivado, convert tar -> AGFI/AFI. Then terminate the instance at the end.
        global_build_config = buildconf dicitonary
        bypass: since this function takes a long time, bypass just returns for
        testing purposes when set to True. """

        # The default error-handling procedure. Send an email and teardown instance
        def on_build_failure():
            message_title = "FireSim FPGA Build Failed"

            message_body = "Your FPGA build failed for triplet: " + buildconf.get_chisel_triplet()
            message_body += ".\nInspect the log output from IP address " + env.host_string + " for more information."

            send_firesim_notification(message_title, message_body)

            rootLogger.info(message_title)
            rootLogger.info(message_body)
            rootLogger.info("Terminating the build instance now.")
            buildconf.terminate_build_instance()

        rootLogger.info("Running process to build AGFI from verilog.")

        # First, Produce dcp/tar for design. Runs on remote machines, out of
        # /home/centos/firesim-build/ """
        ddir = get_deploy_dir()
        buildconf = global_build_config.get_build_by_ip(env.host_string)
        builddir = buildconf.get_build_dir_name()
        # local AWS build directory; might have config-specific changes to fpga flow
        fpgabuilddir = "hdk/cl/developer_designs/cl_" + buildconf.get_chisel_triplet()
        remotefpgabuilddir = "hdk/cl/developer_designs/cl_firesim"

        if bypass:
            buildconf.terminate_build_instance(buildconf)
            return

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
        vivado_result = 0
        with prefix('cd /home/centos/firesim-build/platforms/f1/aws-fpga'), \
            prefix('source hdk_setup.sh'), \
            prefix('export CL_DIR=/home/centos/firesim-build/platforms/f1/aws-fpga/' + remotefpgabuilddir), \
            prefix('cd $CL_DIR/build/scripts/'), InfoStreamLogger('stdout'), InfoStreamLogger('stderr'), \
            settings(warn_only=True):
            vivado_result = run('./aws_build_dcp_from_cl.sh -foreground').return_code

        # rsync in the reverse direction to get build results
        with StreamLogger('stdout'), StreamLogger('stderr'):
            rsync_cap = rsync_project(local_dir="""{}/results-build/{}/""".format(ddir, builddir),
                        remote_dir='/home/centos/firesim-build/platforms/f1/aws-fpga/' + remotefpgabuilddir,
                        ssh_opts="-o StrictHostKeyChecking=no", upload=False, extra_opts="-l",
                        capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        if vivado_result != 0:
            on_build_failure()
            return

        if not aws_create_afi(global_build_config, buildconf):
            on_build_failure()
            return

        rootLogger.info("Terminating the build instance now.")
        buildconf.terminate_build_instance()

    def get_deploy_dir(self):
        """ Must use local here. determine where the firesim/deploy dir is """
        with StreamLogger('stdout'), StreamLogger('stderr'):
            deploydir = local("pwd", capture=True)
        return deploydir

    def aws_create_afi(self, buildconf):
        """
        Convert the tarball created by Vivado build into an Amazon Global FPGA Image (AGFI)

        :return: None on error
        """
        ## next, do tar -> AGFI
        ## This is done on the local copy

        ddir = get_deploy_dir()
        results_builddir = buildconf.get_build_dir_name()

        afi = None
        agfi = None
        s3bucket = global_build_config.s3_bucketname
        afiname = buildconf.name

        # construct the "tags" we store in the AGFI description
        tag_buildtriplet = buildconf.get_chisel_triplet()
        tag_deploytriplet = tag_buildtriplet
        if buildconf.deploytriplet != "None":
            tag_deploytriplet = buildconf.deploytriplet

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

            rootLogger.info("Build complete! AFI ready. See {}.".format(pjoin(hwdb_entry_file_location,afiname)))
            return True
        else:
            return

    def __str__(self):
        return pprint.pformat(vars(self))
