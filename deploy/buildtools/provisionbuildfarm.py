import logging

from awstools.awstools import *
import sys

rootLogger = logging.getLogger()

class ProvisionBuildFarm:
    def __init__(self, build_config, args):
        self.build_config = build_config
        self.args = args
        self.override_remote_build_dir = None

        for arg in args:
            split_k_v = [s for s in arg.split("=")]
            key = split_k_v[0]
            value = split_k_v[1]

            if key == "rembuilddir":
                self.override_remote_build_dir = value

    def launch_build_instance(self):
        return

    def wait_on_instance_launch(self):
        return

    def get_build_instance_private_ip(self):
        return

    def terminate_build_instance(self):
        return

class ProvisionDefaultBuildFarm(ProvisionBuildFarm):
    def launch_build_instance(self):
        rootLogger.info("No launch needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return None

    def wait_on_instance_launch(self):
        rootLogger.info("No waiting needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return

    def get_build_instance_private_ip(self):
        return self.build_config.build_host

    def terminate_build_instance(self):
        rootLogger.info("No termination needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return

class ProvisionEC2BuildFarm(ProvisionBuildFarm):
    def __init__(self, build_config, args):
        ProvisionBuildFarm.__init__(self, build_config, args)

        # default values
        self.instance_type = "z1d.2xlarge"

        for arg in args:
            split_k_v = [s for s in arg.split("=")]
            key = split_k_v[0]
            value = split_k_v[1]

            if key == "insttype":
                self.instance_type = value

    def launch_build_instance(self):
        globalbuildconf = self.build_config.global_build_config
        buildconf = self.build_config

        # get access to the runfarmprefix, which we will apply to build
        # instances too now.
        aws_resource_names_dict = aws_resource_names()
        # just duplicate the runfarmprefix for now. This can be None,
        # in which case we give an empty build farm prefix
        build_farm_prefix = aws_resource_names_dict['runfarmprefix']

        build_instance_market = globalbuildconf.build_instance_market
        spot_interruption_behavior = globalbuildconf.spot_interruption_behavior
        spot_max_price = globalbuildconf.spot_max_price

        buildfarmprefix = '' if build_farm_prefix is None else build_farm_prefix
        num_instances = 1
        buildconf.launched_instance_object = launch_instances(
            self.instance_type,
            num_instances,
            build_instance_market,
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

    def wait_on_instance_launch(self):
        wait_on_instance_launches([self.build_config.launched_instance_object])

    def get_build_instance_private_ip(self):
        """ Get the private IP of the instance running this build. """
        return self.build_config.launched_instance_object.private_ip_address

    def terminate_build_instance(self):
        """ Terminate the instance running this build. """
        instance_ids = get_instance_ids_for_instances([self.build_config.launched_instance_object])
        rootLogger.info("Terminating build instances {}".format(instance_ids))
        terminate_instances(instance_ids, dryrun=False)
