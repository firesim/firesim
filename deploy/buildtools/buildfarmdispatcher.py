import logging

from awstools.awstools import *

rootLogger = logging.getLogger()

class BuildFarmDispatcher:
    """ Abstract class to manage how to handle instances (launch, build, terminate, etc). """

    def __init__(self, build_config, arg_dict):
        """ Initialization function.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        self.build_config = build_config
        self.arg_dict = arg_dict
        # used to override where to do the fpga build (if done remotely)
        self.override_remote_build_dir = arg_dict.get("remotebuilddir")
        self.is_local = False

    def launch_build_instance(self):
        """ Launch instance """
        raise NotImplementedError

    def wait_on_instance_launch(self):
        """ Ensure instance is launched and ready to be used """
        raise NotImplementedError

    def get_build_instance_private_ip(self):
        """ Get IP address associated with this dispatched instance """
        raise NotImplementedError

    def terminate_build_instance(self):
        """ Terminate the instance """
        raise NotImplementedError

class DefaultBuildFarmDispatcher(BuildFarmDispatcher):
    """ Default dispatcher class that uses the IP address given as the build host. """

    def __init__(self, build_config, arg_dict):
        """ Initialization function. Sets IP address and determines if it is localhost.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        BuildFarmDispatcher.__init__(self, build_config, arg_dict)

        # default options
        self.ip_addr = arg_dict.get('ipaddr')

        if self.ip_addr == "localhost":
            self.is_local = True

        rootLogger.info("Using host {} for {}".format(self.build_config.build_host, self.build_config.get_chisel_triplet()))

    def launch_build_instance(self):
        """ Launch instance. In this case, no launch is needed since IP address should be pre-setup. """
        return

    def wait_on_instance_launch(self):
        """ Wait for instance launch. In this case, no launch is needed, there is no need to wait. """
        return

    def get_build_instance_private_ip(self):
        """ Get IP address associated with this dispatched instance.
        Returns:
            (str): IP address given as the dispatcher arg
        """

        return self.ip_addr

    def terminate_build_instance(self):
        """ Terminate instance. In this case, no terminate is needed since nothing was launched. """
        return

class EC2BuildFarmDispatcher(BuildFarmDispatcher):
    """ Dispatcher class to manage an AWS EC2 instance as the build host. """

    def __init__(self, build_config, arg_dict):
        """ Initialization function. Setup AWS instance variables.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        BuildFarmDispatcher.__init__(self, build_config, arg_dict)

        # aws specific options
        self.instance_type = arg_dict.get('instancetype')
        self.build_instance_market = arg_dict.get('buildinstancemarket')
        self.spot_interruption_behavior = arg_dict.get('spotinterruptionbehavior')
        self.spot_max_price = arg_dict.get('spotmaxprice')
        self.launched_instance_object = None

        self.is_local = False

    def launch_build_instance(self):
        """ Launch an AWS EC2 instance for the build config. """

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
        self.launched_instance_object = launch_instances(
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
        """ Wait for EC2 instance launch. """
        wait_on_instance_launches([self.launched_instance_object])

    def get_build_instance_private_ip(self):
        """ Get IP address associated with this dispatched instance.

        Returns:
            (str): IP address of EC2 build host
        """

        return self.launched_instance_object.private_ip_address

    def terminate_build_instance(self):
        """ Terminate the EC2 instance running this build. """
        instance_ids = get_instance_ids_for_instances([self.launched_instance_object])
        rootLogger.info("Terminating build instances {}".format(instance_ids))
        terminate_instances(instance_ids, dryrun=False)
