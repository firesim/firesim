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
        # Used for local platform
        self.assigned_ip = None

    def __repr__(self):
        return "BuildConfig obj:\n" + pprint.pformat(vars(self), indent=10)

    def get_chisel_triplet(self):
        return """{}-{}-{}""".format(self.DESIGN, self.TARGET_CONFIG, self.PLATFORM_CONFIG)

    def launch_build_instance(self, build_instance_market,
                          spot_interruption_behavior, spot_max_price,
                              buildfarmprefix):
        """ Launch an instance to run this build.
        buildfarmprefix can be None.
        """
        buildfarmprefix = '' if buildfarmprefix is None else buildfarmprefix
        num_instances = 1
        self.launched_instance_object = launch_instances(self.instancetype,
                          num_instances, build_instance_market,
                          spot_interruption_behavior,
                          spot_max_price,
                          blockdevices=[
                              {
                                  'DeviceName': '/dev/sda1',
                                  'Ebs': {
                                      'VolumeSize': 200,
                                      'VolumeType': 'gp2',
                                  },
                              },
                          ],
                          tags={ 'fsimbuildcluster': buildfarmprefix },
                          randomsubnet=True)[0]

    def get_launched_instance_object(self):
        """ Get the instance object returned by boto3 for this build. """
        return self.launched_instance_object

    def get_build_instance_private_ip(self):
        """ Get the private IP of the instance running this build. """
        if assigned_ip:
            ip = assigned_ip
        else:
            ip = self.launched_instance_object.private_ip_address
        return ip

    def set_build_instance_private_ip(self, ip):
        assigned_ip = ip

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
