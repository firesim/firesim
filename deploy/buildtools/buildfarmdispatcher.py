import logging

from awstools.awstools import *

rootLogger = logging.getLogger()

class BuildFarmDispatcher:
    def __init__(self, build_config, arg_dict):
        self.build_config = build_config
        self.arg_dict = arg_dict
        self.override_remote_build_dir = arg_dict.get("remotebuilddir")
        self.is_local = False

    def launch_build_instance(self):
        return

    def wait_on_instance_launch(self):
        return

    def get_build_instance_private_ip(self):
        return

    def terminate_build_instance(self):
        return

class DefaultBuildFarmDispatcher(BuildFarmDispatcher):
    def __init__(self, build_config, arg_dict):
        BuildFarmDispatcher.__init__(self, build_config, arg_dict)

        # default options
        self.ip_addr = arg_dict.get('ipaddr')

        if self.ip_addr == "localhost":
            self.is_local = True

    def launch_build_instance(self):
        rootLogger.info("No launch needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return None

    def wait_on_instance_launch(self):
        rootLogger.info("No waiting needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return

    def get_build_instance_private_ip(self):
        return self.ip_addr

    def terminate_build_instance(self):
        rootLogger.info("No termination needed for {} host (using {})".format(self.build_config.get_chisel_triplet(), self.build_config.build_host))
        return

class EC2BuildFarmDispatcher(BuildFarmDispatcher):
    def __init__(self, build_config, arg_dict):
        BuildFarmDispatcher.__init__(self, build_config, arg_dict)

        # aws specific options
        self.instance_type = arg_dict.get('instancetype')
        self.build_instance_market = arg_dict.get('buildinstancemarket')
        self.spot_interruption_behavior = arg_dict.get('spotinterruptionbehavior')
        self.spot_max_price = arg_dict.get('spotmaxprice')

        self.is_local = False

    def launch_build_instance(self):
        buildconf = self.build_config

        # get access to the runfarmprefix, which we will apply to build
        # instances too now.
        aws_resource_names_dict = aws_resource_names()
        # just duplicate the runfarmprefix for now. This can be None,
        # in which case we give an empty build farm prefix
        build_farm_prefix = aws_resource_names_dict['runfarmprefix']

        build_instance_market = self.build_instance_market
        spot_interruption_behavior = self.spot_interruption_behavior
        spot_max_price = self.spot_max_price

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
