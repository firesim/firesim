""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
import logging
import sys

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import auto_create_bucket, get_snsname_arn
from buildtools.buildconfig import BuildConfig

rootLogger = logging.getLogger()

class BuildConfigFile:
    """ Class representing the "global" build config file i.e. sample_config_build.ini. """

    def __init__(self, args):
        """ Initialize function.

        Parameters:
            args (argparse.Namespace): Object holding arg attributes
        """

        if args.launchtime:
            launch_time = args.launchtime
        else:
            launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

        global_build_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        global_build_configfile.optionxform = str
        global_build_configfile.read(args.buildconfigfile)

        # aws specific options
        self.agfistoshare = [x[0] for x in global_build_configfile.items('agfistoshare')]
        self.acctids_to_sharewith = [x[1] for x in global_build_configfile.items('sharewithaccounts')]

        # this is a list of actual builds to run
        builds_to_run_list = map(lambda x: x[0], global_build_configfile.items('builds'))

        build_recipes_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        build_recipes_configfile.optionxform = str
        build_recipes_configfile.read(args.buildrecipesconfigfile)

        build_hosts_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        build_hosts_configfile.optionxform = str
        build_hosts_configfile.read(args.buildhostsconfigfile)

        build_recipes = dict()
        for section in build_recipes_configfile.sections():
            build_recipes[section] = BuildConfig(
                section,
                dict(build_recipes_configfile.items(section)),
                build_hosts_configfile,
                self,
                launch_time)

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(map(lambda x: build_recipes[x], builds_to_run_list))
        self.build_ip_set = set()

    def setup(self):
        """ Setup based on the types of buildhosts """
        for build in self.builds_list:
            auto_create_bucket(build.s3_bucketname)

        #check to see email notifications can be subscribed
        get_snsname_arn()

    def request_build_hosts(self):
        """ Launch an instance for the builds. Exits the program if an IP address is reused. """
        # TODO: optimization: batch together items using the same buildhost
        for build in self.builds_list:
            build.build_host_dispatcher.request_build_host()
            num_ips = len(self.build_ip_set)
            ip = build.build_host_dispatcher.get_build_host_ip()
            self.build_ip_set.add(ip)
            if num_ips == len(self.build_ip_set):
                rootLogger.critical("ERROR: Duplicate {} IP used when launching instance".format(ip))
                self.release_build_hosts()
                sys.exit(1)

    def wait_on_build_host_initializations(self):
        """ Block until all build instances are launched """
        for build in self.builds_list:
            build.build_host_dispatcher.wait_on_build_host_initialization()

    def release_build_hosts(self):
        """ Terminate all build instances that are launched """
        for build in self.builds_list:
            build.build_host_dispatcher.release_build_host()

    def get_build_by_ip(self, nodeip):
        """ For a particular IP (aka instance), return the build config it is running.

        Parameters:
            nodeip (str): IP address of build wanted
        Returns:
            (BuildConfig or None): Build config of input IP or None
        """

        for build in self.builds_list:
            if build.build_host_dispatcher.get_build_host_ip() == nodeip:
                return build
        return None

    def get_build_host_ips(self):
        """ Get all the build instance IPs (later passed to fabric as hosts).

        Returns:
            (list[str]): List of IP addresses to build on
        """
        return list(self.build_ip_set)

    def get_builds_list(self):
        """ Get all the build configurations.

        Returns:
            (list[BuildConfig]): List of build configs
        """

        return self.builds_list

    def __str__(self):
        """ Print the class.

        Returns:
            (str): String representation of the class
        """
        return pprint.pformat(vars(self))

