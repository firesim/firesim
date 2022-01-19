""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import pprint
from importlib import import_module

from awstools.awstools import *
from buildtools.buildfarmhostdispatcher import *

def inheritors(klass):
    subclasses = set()
    work = [klass]
    while work:
	parent = work.pop()
	for child in parent.__subclasses__():
	    if child not in subclasses:
		subclasses.add(child)
		work.append(child)
    return subclasses

class BuildConfig:
    """ Represents a single build configuration used to build RTL/drivers/AFIs. """

    def __init__(self, name, recipe_config_dict, build_farm_hosts_configfile, global_build_config, launch_time):
        """ Initialization function.

        Parameters:
            name (str): Name of config i.e. name of build_recipe.ini section
            recipe_config_dict (dict): build_recipe.ini options associated with name
            build_farm_hosts_configfile (dict): Parsed representation of build_farm_hosts.ini file
            global_build_config (BuildConfigFile): Global build config file
            launch_time (str): Time manager was launched
        """

        self.name = name
        self.global_build_config = global_build_config

        self.TARGET_PROJECT = recipe_config_dict.get('TARGET_PROJECT')
        self.DESIGN = recipe_config_dict['DESIGN']
        self.TARGET_CONFIG = recipe_config_dict['TARGET_CONFIG']
        self.deploytriplet = recipe_config_dict['deploy-triplet']
        self.launch_time = launch_time

        # run platform specific options
        self.PLATFORM_CONFIG = recipe_config_dict['PLATFORM_CONFIG']
        self.post_build_hook = recipe_config_dict['post-build-hook']

        # retrieve the build host section
        self.build_farm_host = recipe_config_dict.get('build-host', "default-build-host")
        build_farm_host_conf_dict = build_farm_hosts_configfile[self.build_farm_host]

        build_farm_host_type = build_farm_host_conf_dict["build-farm-type"]
        build_farm_host_args = build_farm_host_conf_dict["args"]

	build_farm_host_dispatch_dict = dict([(x.NAME, x.__name__) for x in inheritors(BuildHostDispatcher)])

        build_farm_host_dispatcher_class_name = build_farm_host_dispatch_dict[build_farm_host_type]

        # create dispatcher object using class given and pass args to it
        self.build_farm_host_dispatcher = getattr(
            import_module("buildtools.buildhostdispatcher"),
            build_farm_host_dispatcher_class_name)(self, build_farm_host_args)

        self.build_farm_host_dispatcher.parse_args()

        self.fpga_bit_builder_dispatcher_class_name = recipe_config_dict.get('fpga-platform')
        if self.fpga_bit_builder_dispatcher_class_name == None:
            self.fpga_bit_builder_dispatcher_class_name = "F1BitBuilder"
        # create run platform dispatcher object using class given and pass args to it
        self.fpga_bit_builder_dispatcher = getattr(
            import_module("buildtools.bitbuilder"),
            self.fpga_bit_builder_dispatcher_class_name)(self, recipe_config_dict)

        self.fpga_bit_builder_dispatcher.parse_args()

    def __repr__(self):
        """ Print the class.

        Returns:
            (str): String representation of the class
        """

        return "BuildConfig Object:\n" + pprint.pformat(vars(self), indent=10)

    def get_chisel_triplet(self):
        """ Get the unique build-specific '-' deliminated triplet.

        Returns:
            (str): Chisel triplet
        """

        return """{}-{}-{}""".format(self.DESIGN, self.TARGET_CONFIG, self.PLATFORM_CONFIG)

    def get_build_dir_name(self):
        """" Get the name of the local build directory.

        Returns:
            (str): Name of local build directory (based on time/name)
        """
        return """{}-{}""".format(self.launch_time, self.name)

    # Builds up a string for a make invocation using the tuple variables
    def make_recipe(self, recipe):
        """" Create make command based of build config parameters and input.

        Parameters:
            recipe (str): Make variables / target to run
        Returns:
            (str): Fully specified make command
        """

        return """make {} DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} {}""".format(
            "" if self.TARGET_PROJECT is None else "TARGET_PROJECT=" + self.TARGET_PROJECT,
            self.DESIGN,
            self.TARGET_CONFIG,
            self.PLATFORM_CONFIG,
            recipe)
