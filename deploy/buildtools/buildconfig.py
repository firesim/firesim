""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
from importlib import import_module

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import *
from buildtools.buildfarmdispatcher import *
from buildtools.build import *

class BuildConfig:
    """ This represents a SINGLE build configuration. """
    def __init__(self, name, build_config_dict, build_host_conf_dict, build_host_name, global_build_config, launch_time):
        self.name = name
        self.global_build_config = global_build_config

        self.TARGET_PROJECT = build_config_dict.get('TARGET_PROJECT')
        self.DESIGN = build_config_dict['DESIGN']
        self.TARGET_CONFIG = build_config_dict['TARGET_CONFIG']
        self.deploytriplet = build_config_dict['deploytriplet']
        self.launch_time = launch_time

        # run platform specific options
        self.PLATFORM_CONFIG = build_config_dict['PLATFORM_CONFIG']
        self.s3_bucketname = build_config_dict['s3bucketname']
        if valid_aws_configure_creds():
            aws_resource_names_dict = aws_resource_names()
            if aws_resource_names_dict['s3bucketname'] is not None:
                # in tutorial mode, special s3 bucket name
                self.s3_bucketname = aws_resource_names_dict['s3bucketname']
        self.post_build_hook = build_config_dict['postbuildhook']

        self.build_host = build_host_name
        self.build_farm_dispatcher_class_name = build_host_conf_dict['providerclass']
        del build_host_conf_dict['providerclass']
        self.build_farm_dispatcher = getattr(
            import_module("buildtools.buildfarmdispatcher"),
            self.build_farm_dispatcher_class_name)(self, build_host_conf_dict)

    def __repr__(self):
        return "BuildConfig Object:\n" + pprint.pformat(vars(self), indent=10)

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
