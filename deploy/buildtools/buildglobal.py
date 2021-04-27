""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
from fabric.api import *
import logging

from runtools.runtime_config import RuntimeHWDB
from buildtools.buildconfig import BuildConfig
from buildtools.buildlocal import GlobalLocalBuildConfig
from buildtools.buildafi import GlobalAwsBuildConfig

rootLogger = logging.getLogger()

class GlobalBuildConfig:
    """ Configuration class for builds. This is the "global" configfile,
    i.e. sample_config_build.ini
    """

    def __init__(self, args):
        if args.launchtime:
            launch_time = args.launchtime
        else:
            launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

        global_build_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        global_build_configfile.optionxform = str
        global_build_configfile.read(args.buildconfigfile)

        builds_to_run_list = map(lambda x: x[0], global_build_configfile.items('builds'))

        build_recipes_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        build_recipes_configfile.optionxform = str
        build_recipes_configfile.read(args.buildrecipesconfigfile)

        self.builds_list = []
        for build_name in builds_to_run_list:
            self.builds_list.append(BuildConfig(
                build_name,
                dict(build_recipes_configfile.items(build_name)),
                launch_time))

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        # setup the particular host_platform
        self.host_platform = global_build_configfile.get('buildfarmconfig', 'hostplatform')
        if self.host_platform == "aws":
            self.host_platform_config = GlobalAwsBuildConfig(args, global_build_configfile)
        elif self.host_platform == "local":
            self.host_platform_config = GlobalLocalBuildConfig(args, global_build_configfile, self.builds_list)
        else:
            sys.exit('Invalid host platform: {}'.format(host_platform))

    def launch_build_instances(self):
        """ Launch an instance for the builds we want to do """
        self.host_platform_config.launch_build_instances()

    def wait_build_instances(self):
        """ Block until all build instances are launched """
        self.host_platform_config.wait_build_instances()

    def terminate_all_build_instances(self):
        """ Terminate all build instances that are launched """
        self.host_platform_config.terminate_all_build_instances()

    def host_platform_init(self):
        """ Specific init before running RTL builds """
        self.host_platform_config.init()

    def replace_rtl(self, buildconf):
        """ Run chisel/firrtl/fame-1, produce verilog for fpga build.
        THIS ALWAYS RUNS LOCALLY."""
        rootLogger.info('Running replace-rtl to generate verilog for {}'.format(buildconf.get_chisel_triplet()))
        self.host_platform_config.replace_rtl(buildconf)

    @parallel
    def fpga_build(self, bypass=False):
        """ Run specific steps for building FPGA image. """
        self.host_platform_config.fpga_build(self)
        return

    def get_build_by_ip(self, nodeip):
        """ For a particular private IP (aka instance), return the BuildConfig
        that it's supposed to be running. """
        for build in self.builds_list:
            if build.get_build_instance_private_ip() == nodeip:
                return build
        return None

    def get_build_instance_ips(self):
        """ Return a list of all the build instance IPs, i.e. hosts to pass to
        fabric. """
        return map(lambda x: x.get_build_instance_private_ip(), self.builds_list)

    def get_builds_list(self):
        return self.builds_list

    def __str__(self):
        return pprint.pformat(vars(self))
