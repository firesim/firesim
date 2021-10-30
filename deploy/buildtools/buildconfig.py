""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
from importlib import import_module

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import *
from buildtools.buildhostdispatcher import *
from buildtools.build import *

class BuildConfig:
    """ Represents a single build configuration used to build RTL/drivers/AFIs. """

    def __init__(self, name, recipe_config_dict, build_hosts_configfile, global_build_config, launch_time):
        """ Initialization function.

        Parameters:
            name (str): Name of config i.e. name of build_recipe.ini section
            recipe_config_dict (dict): build_recipe.ini options associated with name
            build_hosts_configfile (configparser.ConfigParser): Parsed representation of build_hosts.ini file
            global_build_config (BuildConfigFile): Global build config file
            launch_time (str): Time manager was launched
        """

        self.name = name
        self.global_build_config = global_build_config

        self.TARGET_PROJECT = recipe_config_dict.get('TARGET_PROJECT')
        self.DESIGN = recipe_config_dict['DESIGN']
        self.TARGET_CONFIG = recipe_config_dict['TARGET_CONFIG']
        self.deploytriplet = recipe_config_dict['deploytriplet']
        self.launch_time = launch_time

        # run platform specific options
        self.PLATFORM_CONFIG = recipe_config_dict['PLATFORM_CONFIG']
        self.s3_bucketname = recipe_config_dict['s3bucketname']
        if valid_aws_configure_creds():
            aws_resource_names_dict = aws_resource_names()
            if aws_resource_names_dict['s3bucketname'] is not None:
                # in tutorial mode, special s3 bucket name
                self.s3_bucketname = aws_resource_names_dict['s3bucketname']
        self.post_build_hook = recipe_config_dict['postbuildhook']

        # retrieve the build host section
        self.build_host = recipe_config_dict.get('buildhost')
        if self.build_host == None:
            self.build_host = "defaultbuildhost"
        build_host_conf_dict = dict(build_hosts_configfile.items(self.build_host))

        self.build_host_dispatcher_class_name = build_host_conf_dict['providerclass']
        del build_host_conf_dict['providerclass']
        # create dispatcher object using class given and pass args to it
        self.build_host_dispatcher = getattr(
            import_module("buildtools.buildhostdispatcher"),
            self.build_host_dispatcher_class_name)(self, build_host_conf_dict)

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
