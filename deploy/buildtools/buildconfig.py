""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
import sys

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import *

def str2class(classname):
        return getattr(sys.modules[__name__], classname)

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
        self.build_host = None
        self.provision_build_farm_class_name = None

    def add_host(self, host, launch_func_name):
        self.build_host = host
        self.provision_build_farm_class_name = launch_func_name

    def __repr__(self):
        return "BuildConfig obj:\n" + pprint.pformat(vars(self), indent=10)

    def get_chisel_triplet(self):
        return """{}-{}-{}""".format(self.DESIGN, self.TARGET_CONFIG, self.PLATFORM_CONFIG)

    def get_build_dir_name(self):
        """" Get the name of the local build directory. """
        return """{}-{}""".format(self.launch_time, self.name)

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
        if args.launchtime:
            launch_time = args.launchtime
        else:
            launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

        global_build_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        global_build_configfile.optionxform = str
        global_build_configfile.read(args.buildconfigfile)

        ## TODO: This errors if not using aws
        ## aws specific options
        #self.s3_bucketname = global_build_configfile.get('afibuild', 's3bucketname')
        #aws_resource_names_dict = aws_resource_names()
        #if aws_resource_names_dict['s3bucketname'] is not None:
        #    # in tutorial mode, special s3 bucket name
        #    self.s3_bucketname = aws_resource_names_dict['s3bucketname']
        #self.build_instance_market = global_build_configfile.get('afibuild', 'buildinstancemarket')
        #self.spot_interruption_behavior = global_build_configfile.get('afibuild', 'spotinterruptionbehavior')
        #self.spot_max_price = global_build_configfile.get('afibuild', 'spotmaxprice')
        #self.post_build_hook = global_build_configfile.get('afibuild', 'postbuildhook')
        #self.agfistoshare = [x[0] for x in global_build_configfile.items('agfistoshare')]
        #self.acctids_to_sharewith = [x[1] for x in global_build_configfile.items('sharewithaccounts')]

        # this is a list of actual builds to run
        builds_to_run_list = map(lambda x: x[0], global_build_configfile.items('builds'))

        # get host providers
        self.host_providers = dict(global_build_configfile.items("hostproviders"))
        self.build_hosts = dict(global_build_configfile.items("buildhosts"))

        if len(self.build_hosts) != len(builds_to_run_list):
            print("ERROR: needs builds.len != buildhosts.len")
            sys.exit(1)

        # map build to build recipies
        build_recipes_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        # make option names case sensitive
        build_recipes_configfile.optionxform = str
        build_recipes_configfile.read(args.buildrecipesconfigfile)

        build_recipes = dict()
        for section in build_recipes_configfile.sections():
            build_recipes[section] = BuildConfig(section,
                dict(build_recipes_configfile.items(section)),
                launch_time)

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(map(lambda x: build_recipes[x], builds_to_run_list))

        for idx, build in enumerate(self.builds_list):
            # get corresponding host and use it
            host = self.build_hosts[str(idx)]
            if host in self.host_providers.keys():
                build.add_host(host, self.host_providers[host])
            else:
                build.add_host(host, None)

    def setup(self):
        """ Setup based on the types of buildhosts """

        if "f1" in list(map(lambda x: x.build_host, self.builds_list)):
            auto_create_bucket(self.s3_bucketname)
            #check to see email notifications can be subscribed
            get_snsname_arn()

    def launch_build_instances(self):
        """ Launch an instance for the builds we want to do """

        for build in self.builds_list:
            if build.provision_build_farm_class_name:
                build.launched_instance_object = str2class(build.provision_build_farm_class_name).launch_build_instance(self)
            else:
                build.launched_instance_object = ProvisionDefaultBuildFarm.launch_build_instance(self)


    def wait_build_instances(self):
        """ block until all build instances are launched """
        for build in self.builds_list:
            instance = build.get_launched_instance_object()
            if build.provision_build_farm_class_name:
                str2class(build.provision_build_farm_class_name).wait_on_instance_launch(instance)
            else:
                ProvisionDefaultBuildFarm.wait_on_instance_launch(instance)

    def terminate_all_build_instances(self):
        for build in self.builds_list:
            instance = build.get_launched_instance_object()
            if build.provision_build_farm_class_name:
                str2class(build.provision_build_farm_class_name).terminate_build_instance(instance)
            else:
                ProvisionDefaultBuildFarm.terminate_build_instance(instance)

    def get_build_by_ip(self, nodeip):
        """ For a particular private IP (aka instance), return the BuildConfig
        that it's supposed to be running. """
        for build in self.builds_list:
            instance = build.get_launched_instance_object()
            if build.provision_build_farm_class_name:
                if str2class(build.provision_build_farm_class_name).get_build_instance_private_ip(instance) == nodeip:
                    return build
            else:
                if ProvisionDefaultBuildFarm.get_build_instance_private_ip(instance) == nodeip:
                    return build
        return None

    def get_build_instance_ips(self):
        """ Return a list of all the build instance IPs, i.e. hosts to pass to
        fabric. """
        ip_list = []
        for build in self.builds_list:
            instance = build.get_launched_instance_object()
            if build.provision_build_farm_class_name:
                ip_list.append(str2class(build.provision_build_farm_class_name).get_build_instance_private_ip(instance))
            else:
                ip_list.append(ProvisionDefaultBuildFarm.get_build_instance_private_ip(instance))
        return ip_list

    def get_builds_list(self):
        return self.builds_list

    def __str__(self):
        return pprint.pformat(vars(self))

