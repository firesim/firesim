from __future__ import with_statement
import logging
import os
import sys

from fabric.api import *
from util.streamlogger import StreamLogger, InfoStreamLogger

rootLogger = logging.getLogger()

class GlobalLocalBuildConfig:
    def __init__(self, args, global_build_configfile, build_recipes_list):
        self.local_ip_list = map(lambda x: x[0], global_build_configfile.items('localips'))
        self.fpga_type = global_build_configfile.get('local', 'fpgatype')

        if len(self.local_ip_list) != len(build_recipes_list):
            sys.exit('Not enough IP addresses to assign to each build. {} IPs < {} builds.'.format(
                len(self.local_ip_list),
                len(build_recipes_list)))

        for item in zip(build_recipes_list, self.local_ip_list):
            item[0].set_build_instance_private_ip(item[1])

    def launch_build_instances(self):
        return

    def wait_build_instances(self):
        return

    def terminate_all_build_instances(self):
        return

    def host_platform_init(self):
        return

    def replace_rtl(self, buildconf):
        """ Run chisel/firrtl/fame-1, produce verilog for fpga build.
        THIS ALWAYS RUNS LOCALLY."""

        builddir = buildconf.get_build_dir_name()
        ddir = self.get_deploy_dir()

        # build the rtl
        with prefix('cd ' + ddir + '/../'), \
            prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
            prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
            prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
            prefix('source sourceme-f1-manager.sh'), \
            prefix('cd sim/'), \
            InfoStreamLogger('stdout'), \
            InfoStreamLogger('stderr'):
            run(buildconf.make_recipe("replace-rtl"))
            run("""mkdir -p {}/results-build/{}/""".format(ddir, builddir))

        # build the fpga driver (RTL, C++) that corresponds with this version of the RTL
        # TODO: Verify later
        print("Building FPGA driver RTL and C++")
        #with prefix('cd ' + ddir + '/../'), \
        #    prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
        #    prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
        #    prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
        #    prefix('source sourceme-f1-manager.sh'), \
        #    prefix('cd sim/'), \
        #    StreamLogger('stdout'), \
        #    StreamLogger('stderr'):
        #    run(buildconfig.make_recipe(conf.fpga_type))

    def fpga_build(self, global_build_config, bypass=False):
        """ Run specific steps for building FPGA image. """
        print("Not there yet")
        return

    def get_deploy_dir(self):
        """ Must use local here. determine where the firesim/deploy dir is """
        with StreamLogger('stdout'), StreamLogger('stderr'):
            deploydir = local("pwd", capture=True)
        return deploydir

    def __str__(self):
        return pprint.pformat(vars(self))
