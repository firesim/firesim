""" This converts the build configuration files into something usable by the
manager """

from __future__ import with_statement
from time import strftime, gmtime
import ConfigParser
import pprint
import sys
import json
import time
import random
import string
import logging

from fabric.api import *
from fabric.contrib.console import confirm
from fabric.contrib.project import rsync_project
from awstools.afitools import *
from awstools.awstools import *
from util.streamlogger import StreamLogger, InfoStreamLogger

rootLogger = logging.getLogger()

def get_deploy_dir():
    """ Must use local here. determine where the firesim/deploy dir is """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

class BitBuilder:
    def __init__(self, build_config, arg_dict):
        self.build_config = build_config
        self.arg_dict = arg_dict
        return

    def parse_args(self):
        # no default args
        return

    def get_arg(self, arg_wanted):
        """ Retrieve argument from arg dict and error if not found.

        Parameters:
            arg_wanted (str): Argument to get value of
        Returns:
            (str or None): Value of argument wanted
        """
        if not self.arg_dict.has_key(arg_wanted):
            rootLogger.critical("ERROR: Unable to find arg {} for {}".format(arg_wanted, self.__name__))
            sys.exit(1)
        return self.arg_dict.get(arg_wanted)

    def setup(self):
        raise NotImplementedError

    def replace_rtl(self):
        raise NotImplementedError

    def build_driver(self):
        raise NotImplementedError

    def build_bitstream(self, bypass=False):
        raise NotImplementedError

    def write_to_built_hwdb_entry(self, name, hwdb_entry):
        """ Write HWDB entry out to file in built_hwdb_entries area

        Parameters:
            name (str): name of HWDB entry
            hwdb_entry (str): string version of hwdb entry
        Returns:
            (str): Path to HWDB written
        """

        local_deploy_dir = get_deploy_dir()

        # for convenience when generating a bunch of images. you can just
        # cat all the files in this directory after your builds finish to get
        # all the entries to copy into config_hwdb.ini
        hwdb_entry_file_location = """{}/built-hwdb-entries/""".format(local_deploy_dir)
        hwdb_entry_file_path = """{}/built-hwdb-entries/{}""".format(local_deploy_dir, name)
        local("mkdir -p " + hwdb_entry_file_location)
        with open(hwdb_entry_file_location + "/" + name, "w") as outputfile:
            outputfile.write(hwdb_entry)

    def run_post_build_hook(self, results_build_dir):
        """ Execute post_build_hook if it exists

        Parameters:
            results_build_dir (str): name of folder to pass to post_build_hook
        """

        if self.build_config.post_build_hook:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                localcap = local("""{} {}""".format(self.build_config.post_build_hook,
                                                    results_build_dir,
                                                    capture=True))
                rootLogger.debug("[localhost] " + str(localcap))
                rootLogger.debug("[localhost] " + str(localcap.stderr))

    def create_hwdb_entry(self, name, platform_name, run_platform_lines):
        """ Create and printout a HWDB entry

        Parameters:
            name (str): name of HWDB entry
            platform_name (str): name of platform to run on
            run_platform_lines (list): string list of lines to add to hwdb entry
        Returns:
            (str): String HWDB entry
        """

        hwdb_entry = "[" + name + "]\n"
        hwdb_entry += "platform=" + platform_name + "\n"
        for l in run_platform_lines:
            hwdb_entry += l + "\n"
        hwdb_entry += "deploytripletoverride=None\n"
        hwdb_entry += "customruntimeconfig=None\n"

        return hwdb_entry

class F1BitBuilder(BitBuilder):
    def __init__(self, build_config, arg_dict):
        BitBuilder.__init__(self, build_config, arg_dict)

        self.s3_bucketname = None

    def parse_args(self):
        """ Parse build host arguments. """
        # get default arguments
        BitBuilder.parse_args(self)

        self.s3_bucketname = self.get_arg('s3bucketname')
        if valid_aws_configure_creds():
            aws_resource_names_dict = aws_resource_names()
            if aws_resource_names_dict['s3bucketname'] is not None:
                # in tutorial mode, special s3 bucket name
                self.s3_bucketname = aws_resource_names_dict['s3bucketname']

    def setup(self):
        auto_create_bucket(self.s3_bucketname)

        #check to see email notifications can be subscribed
        get_snsname_arn()

    def replace_rtl(self):
        """ Generate Verilog from build config """
        rootLogger.info("Building Verilog for {}".format(str(self.build_config.get_chisel_triplet())))

        with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=f1 replace-rtl"))

    def build_driver(self):
        """ Build FireSim FPGA driver from build config """
        rootLogger.info("Building FPGA driver for {}".format(str(self.build_config.get_chisel_triplet())))

        with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=f1 driver"))

    def remote_setup(self):
        """ Setup CL_DIR on remote machine

        Returns:
            (str): Path to remote CL_DIR directory (that is setup)
        """

        fpga_build_postfix = "hdk/cl/developer_designs/cl_{}".format(self.build_config.get_chisel_triplet())

        # local paths
        local_awsfpga_dir = "{}/../platforms/f1/aws-fpga".format(get_deploy_dir())

        # remote paths
        remote_home_dir = ""
        with StreamLogger('stdout'), StreamLogger('stderr'):
            remote_home_dir = run('echo $HOME')

        # potentially override build dir
        if self.build_config.build_host_dispatcher.override_remote_build_dir:
            remote_home_dir = self.build_config.build_host_dispatcher.override_remote_build_dir

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

    def build_bitstream(self, bypass=False):
        """ Run Vivado, convert tar -> AGFI/AFI. Then terminate the instance at the end.
        bypass: since this function takes a long time, bypass just returns for
        testing purposes when set to True. """

        if bypass:
            self.build_config.build_host_dispatcher.release_build_host()
            return

        # The default error-handling procedure. Send an email and teardown instance
        def on_build_failure():
            """ Terminate build host and notify user that build failed """

            message_title = "FireSim FPGA Build Failed"

            message_body = "Your FPGA build failed for triplet: " + self.build_config.get_chisel_triplet()

            send_firesim_notification(message_title, message_body)

            rootLogger.info(message_title)
            rootLogger.info(message_body)

            self.build_config.build_host_dispatcher.release_build_host()

        rootLogger.info("Building AWS F1 AGFI from Verilog")

        local_deploy_dir = get_deploy_dir()
        fpga_build_postfix = "hdk/cl/developer_designs/cl_{}".format(self.build_config.get_chisel_triplet())
        local_results_dir = "{}/results-build/{}".format(local_deploy_dir, self.build_config.get_build_dir_name())

        # cl_dir is the cl_dir that is either local or remote
        # if locally no need to copy things around (the makefile should have already created a CL_DIR w. the tuple)
        # if remote (aka not locally) then you need to copy things
        cl_dir = ""
        local_cl_dir = "{}/../platforms/f1/aws-fpga/{}".format(local_deploy_dir, fpga_build_postfix)

        # copy over generated RTL into local CL_DIR before remote
        with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
            run("""mkdir -p {}""".format(local_results_dir))
            run("""cp {}/design/FireSim-generated.sv {}/FireSim-generated.sv""".format(local_cl_dir, local_results_dir))

        if self.build_config.build_host_dispatcher.is_local:
            cl_dir = local_cl_dir
        else:
            cl_dir = remote_setup(self.build_config)

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

        if not aws_create_afi(self.build_config):
            on_build_failure()
            return

        self.build_config.build_host_dispatcher.release_build_host()

    def aws_create_afi(self):
        """
        Convert the tarball created by Vivado build into an Amazon Global FPGA Image (AGFI)

        :return: None on error
        """

        local_deploy_dir = get_deploy_dir()
        local_results_dir = "{}/results-build/{}".format(local_deploy_dir, self.build_config.get_build_dir_name())

        afi = None
        agfi = None
        s3bucket = self.s3_bucketname
        afiname = self.build_config.name

        # construct the "tags" we store in the AGFI description
        tag_buildtriplet = self.build_config.get_chisel_triplet()
        tag_deploytriplet = tag_buildtriplet
        if self.build_config.deploytriplet != "None":
            tag_deploytriplet = self.build_config.deploytriplet

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

            hwdb_entry = self.create_hwdb_entry(afiname, "f1", ["agfi=" + agfi])

            message_title = "FireSim FPGA Build Completed"
            message_body = "Your AGFI has been created!\nAdd\n\n" + hwdb_entry + "\nto your config_hwdb.ini to use this hardware configuration."

            rootLogger.info(message_title)
            rootLogger.info(message_body)
            send_firesim_notification(message_title, message_body)

            written_path = self.write_to_built_hwdb_entry(afiname, hwdb_entry)
            self.run_post_build_hook(results_build_dir)

            rootLogger.info("Build complete! F1 AFI ready. See {}.".format(written_path))
            return True
        else:
            return

class VitisBitBuilder(BitBuilder):
    def __init__(self, build_config, arg_dict):
        BitBuilder.__init__(self, build_config, arg_dict)

    def parse_args(self):
        """ Parse build host arguments. """
        # get default arguments
        BitBuilder.parse_args(self)

    def setup(self):
        return

    def replace_rtl(self):
        """ Generate Verilog from build config """
        rootLogger.info("Building Verilog for {}".format(str(self.build_config.get_chisel_triplet())))

        with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=vitis replace-rtl"))

    def build_driver(self):
        """ Build FireSim FPGA driver from build config """
        rootLogger.info("Building FPGA driver for {}".format(str(self.build_config.get_chisel_triplet())))

        with prefix('cd {}'.format(get_deploy_dir() + "/../")), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(self.build_config.make_recipe("PLATFORM=vitis driver"))

    def remote_setup(self):
        """ Setup CL_DIR on remote machine

        Returns:
            (str): Path to remote CL_DIR directory (that is setup)
        """

        fpga_build_postfix = "cl_{}".format(self.build_config.get_chisel_triplet())

        # local paths
        local_vitis_dir = "{}/../platforms/vitis/".format(get_deploy_dir())

        # remote paths
        remote_home_dir = ""
        with StreamLogger('stdout'), StreamLogger('stderr'):
            remote_home_dir = run('echo $HOME')

        # potentially override build dir
        if self.build_config.build_host_dispatcher.override_remote_build_dir:
            remote_home_dir = self.build_config.build_host_dispatcher.override_remote_build_dir

        remote_build_dir = "{}/firesim-build".format(remote_home_dir)
        remote_vitis_dir = "{}/platforms/vitis".format(remote_build_dir)

        # copy aws-fpga to the build instance.
        # do the rsync, but ignore any checkpoints that might exist on this machine
        # (in case builds were run locally)
        # extra_opts -l preserves symlinks
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run('mkdir -p {}'.format(remote_vitis_dir))
            rsync_cap = rsync_project(
                local_dir=local_vitis_dir,
                remote_dir=remote_vitis_dir,
                ssh_opts="-o StrictHostKeyChecking=no",
                exclude="cl_*",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)
            rsync_cap = rsync_project(
                local_dir="{}/{}/".format(local_vitis_dir, fpga_build_postfix),
                remote_dir='{}/{}'.format(remote_vitis_dir, fpga_build_postfix),
                ssh_opts="-o StrictHostKeyChecking=no",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        return "{}/{}".format(remote_vitis_dir, fpga_build_postfix)

    def build_bitstream(self, bypass=False):
        """ Run Vivado, convert tar -> AGFI/AFI. Then terminate the instance at the end.
        bypass: since this function takes a long time, bypass just returns for
        testing purposes when set to True. """

        if bypass:
            self.build_config.build_host_dispatcher.release_build_host()
            return

        # The default error-handling procedure. Send an email and teardown instance
        def on_build_failure():
            """ Terminate build host and notify user that build failed """

            message_title = "FireSim Vitis FPGA Build Failed"

            message_body = "Your FPGA build failed for triplet: " + self.build_config.get_chisel_triplet()

            rootLogger.info(message_title)
            rootLogger.info(message_body)

            self.build_config.build_host_dispatcher.release_build_host()

        rootLogger.info("Building Vitis FI from Verilog")

        local_deploy_dir = get_deploy_dir()
        fpga_build_postfix = "cl_{}".format(self.build_config.get_chisel_triplet())
        local_results_dir = "{}/results-build/{}".format(local_deploy_dir, self.build_config.get_build_dir_name())

        # cl_dir is the cl_dir that is either local or remote
        # if locally no need to copy things around (the makefile should have already created a CL_DIR w. the tuple)
        # if remote (aka not locally) then you need to copy things
        cl_dir = ""
        local_cl_dir = "{}/../platforms/vitis/{}".format(local_deploy_dir, fpga_build_postfix)

        # copy over generated RTL into local CL_DIR before remote
        with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
            run("""mkdir -p {}""".format(local_results_dir))
            run("""cp {}/design/FireSim-generated.sv {}/FireSim-generated.sv""".format(local_cl_dir, local_results_dir))

        if self.build_config.build_host_dispatcher.is_local:
            cl_dir = local_cl_dir
        else:
            cl_dir = remote_setup(self.build_config)

        vitis_result = 0
        with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
            # TODO: Put script within Vitis area
            # copy script to the cl_dir and execute
            rsync_cap = rsync_project(
                local_dir="{}/../platforms/build-bitstream.sh".format(local_deploy_dir),
                remote_dir="{}/".format(cl_dir),
                ssh_opts="-o StrictHostKeyChecking=no",
                extra_opts="-l", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

            vitis_result = run("{}/build-bitstream.sh {}".format(cl_dir, cl_dir)).return_code

        # put build results in the result-build area
        with StreamLogger('stdout'), StreamLogger('stderr'):
            rsync_cap = rsync_project(
                local_dir="{}/".format(local_results_dir),
                remote_dir="{}".format(cl_dir),
                ssh_opts="-o StrictHostKeyChecking=no", upload=False, extra_opts="-l",
                capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        if vitis_result != 0:
            on_build_failure()
            return

        finame = self.build_config.name
        xclbin_path = cl_dir + "/build_dir.xilinx_u250_gen3x16_xdma_3_1_202020_1/firesim.xclbin"

        results_build_dir = """{}/""".format(local_results_dir)

        hwdb_entry = self.create_hwdb_entry(finame, "vitis", ["xclbin=" + xclbin_path])
        written_path = self.write_to_built_hwdb_entry(finame, hwdb_entry)
        self.run_post_build_hook(results_build_dir)

        message_title = "FireSim FPGA Build Completed"
        message_body = "Your FI has been created!\nAdd\n\n" + hwdb_entry + "\nto your config_hwdb.ini to use this hardware configuration."

        rootLogger.info(message_title)
        rootLogger.info(message_body)

        rootLogger.info("Build complete! Vitis FI ready. See {}.".format(written_path))

        self.build_config.build_host_dispatcher.release_build_host()
