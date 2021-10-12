""" This converts the build configuration files into something usable by the
manager """

from time import strftime, gmtime
import ConfigParser
import pprint
import sys
from importlib import import_module

from runtools.runtime_config import RuntimeHWDB
from awstools.awstools import *
from buildtools.provisionbuildfarm import *
from buildtools.build import *

class BuildConfig:
    """ This represents a SINGLE build configuration. """
    def __init__(self, name, buildconfigdict, global_build_config, launch_time):
        self.name = name
        self.global_build_config = global_build_config

        # parsed options
        self.TARGET_PROJECT = buildconfigdict.get('TARGET_PROJECT')
        self.DESIGN = buildconfigdict['DESIGN']
        self.TARGET_CONFIG = buildconfigdict['TARGET_CONFIG']
        self.PLATFORM_CONFIG = buildconfigdict['PLATFORM_CONFIG']
        self.deploytriplet = buildconfigdict['deploytriplet']
        self.launch_time = launch_time
        self.launched_instance_object = None

        # AJG: assigned by the build recipe
        self.fpga_bit_builder_dispatcher = getattr(import_module("buildtools.bitbuilder"), buildconfigdict['fpgaplatform'])(self)

        # AJG: assigned by the BuildConfigFile
        self.build_host = None
        self.local = False
        self.provision_build_farm_dispatcher = None

    def add_build_host_info(self, build_host, provision_build_farm_class_name, provision_build_farm_args):
        self.build_host = build_host
        # TODO: if given a local ip addr (not localhost) double check that its localhost
        if build_host == "localhost":
            self.local = True

        if provision_build_farm_class_name:
            self.provision_build_farm_dispatcher = getattr(import_module("buildtools.provisionbuildfarm"), provision_build_farm_class_name)(self, provision_build_farm_args)
        else:
            self.provision_build_farm_dispatcher = ProvisionDefaultBuildFarm(self, provision_build_farm_args)

    def __repr__(self):
        return "BuildConfig Object:\n" + pprint.pformat(vars(self), indent=10)

    def get_chisel_triplet(self):
        return """{}-{}-{}""".format(self.DESIGN, self.TARGET_CONFIG, self.PLATFORM_CONFIG)

    def get_launched_instance_object(self):
        """ Get the instance object for this build. """
        return self.launched_instance_object

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
