""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import *

class BuildConfig:
    """ This represents a SINGLE build configuration. """
    def __init__(self, name, buildconfigdict, launch_time):
        self.name = name
        self.TARGET_PROJECT = buildconfigdict.get('TARGET_PROJECT')
        self.DESIGN = buildconfigdict['DESIGN']
        self.TARGET_CONFIG = buildconfigdict['TARGET_CONFIG']
        self.PLATFORM_CONFIG = buildconfigdict['PLATFORM_CONFIG']
        self.instancetype = buildconfigdict['instancetype']
        self.deploytriplet = buildconfigdict['deploytriplet']
        self.launch_time = launch_time
        self.launched_instance_object = None

    def __repr__(self):
        return "BuildConfig obj:\n" + pprint.pformat(vars(self), indent=10)

    def get_chisel_triplet(self):
        return """{}-{}-{}""".format(self.DESIGN, self.TARGET_CONFIG, self.PLATFORM_CONFIG)

    def launch_build_instance(self, build_instance_market,
                          spot_interruption_behavior, spot_max_price):
        """ Launch an instance to run this build. """
        num_instances = 1
        self.launched_instance_object = launch_instances(self.instancetype,
                          num_instances, build_instance_market,
                          spot_interruption_behavior,
                          spot_max_price,
                          randomsubnet=True)[0]

    def get_launched_instance_object(self):
        """ Get the instance object returned by boto3 for this build. """
        return self.launched_instance_object

    def get_build_instance_private_ip(self):
        """ Get the private IP of the instance running this build. """
        return self.launched_instance_object.private_ip_address

    def terminate_build_instance(self):
        """ Terminate the instance running this build. """
        instance_ids = get_instance_ids_for_instances([self.launched_instance_object])
        terminate_instances(instance_ids, dryrun=False)

    def get_build_dir_name(self):
        """" Get the name of the local build directory. """
        return """{}-{}-{}""".format(self.launch_time,
                                     self.get_chisel_triplet(), self.name)

    # Builds up a string for a make invocation using the tuple variables
    def make_recipe(self, recipe):
        return """make {} DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} {}""".format(
            "" if self.TARGET_PROJECT is None else "TARGET_PROJECT=" + self.TARGET_PROJECT,
            self.DESIGN,
            self.TARGET_CONFIG,
            self.PLATFORM_CONFIG,
            recipe)

class GlobalBuildConfig:
    """ Configuration class for builds. This is the "global" configfile, i.e.
    sample_config_build.ini """

    def __init__(self, args):
        launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())
        self.args = args

        global_build_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        global_build_configfile.optionxform = str
        global_build_configfile.read(args.buildconfigfile)

        self.s3_bucketname = \
            global_build_configfile.get('afibuild', 's3bucketname')
        self.build_instance_market = \
                global_build_configfile.get('afibuild', 'buildinstancemarket')
        self.spot_interruption_behavior = \
            global_build_configfile.get('afibuild', 'spotinterruptionbehavior')
        self.spot_max_price = \
                     global_build_configfile.get('afibuild', 'spotmaxprice')
        self.post_build_hook = global_build_configfile.get('afibuild', 'postbuildhook')

        # this is a list of actual builds to run
        builds_to_run_list = map(lambda x: x[0], global_build_configfile.items('builds'))

        build_recipes_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        build_recipes_configfile.optionxform = str
        build_recipes_configfile.read(args.buildrecipesconfigfile)

        build_recipes = dict()
        for section in build_recipes_configfile.sections():
            build_recipes[section] = BuildConfig(section,
                                dict(build_recipes_configfile.items(section)),
                                launch_time)

        self.agfistoshare = [x[0] for x in global_build_configfile.items('agfistoshare')]
        self.acctids_to_sharewith = [x[1] for x in global_build_configfile.items('sharewithaccounts')]
        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(map(lambda x: build_recipes[x], builds_to_run_list))


    def launch_build_instances(self):
        """ Launch an instance for the builds we want to do """
        for build in self.builds_list:
            build.launch_build_instance(self.build_instance_market,
                                        self.spot_interruption_behavior,
                                        self.spot_max_price)

    def wait_build_instances(self):
        """ block until all build instances are launched """
        instances = [build.get_launched_instance_object() for build in self.builds_list]
        wait_on_instance_launches(instances)

    def terminate_all_build_instances(self):
        for build in self.builds_list:
            build.terminate_build_instance()

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

