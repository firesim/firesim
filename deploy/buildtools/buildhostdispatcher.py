import logging
import sys

from awstools.awstools import *

rootLogger = logging.getLogger()

class BuildHostDispatcher:
    """ Abstract class to manage how to handle build hosts (request, wait, release, etc). """

    def __init__(self, build_config, arg_dict):
        """ Initialization function.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        self.build_config = build_config
        self.arg_dict = arg_dict

        # used if dispatcher refers to local build host
        self.is_local = False

        # used to override where to do the fpga build (if done remotely)
        self.override_remote_build_dir = None

    def parse_args(self):
        """ Parse default build host arguments. Can be overridden by child classes. """
        self.override_remote_build_dir = self.get_arg("remotebuilddir")

    def get_arg(self, arg_wanted):
        """ Retrieve argument from arg dict and error if not found.

        Parameters:
            arg_wanted (str): Argument to get value of
        Returns:
            (str or None): Value of argument wanted
        """
        if not arg_dict.has_key(arg_wanted):
            rootLogger.critical("ERROR: Unable to find arg {} for {}".format(arg_wanted, self.__name__))
            sys.exit(1)
        return arg_dict.get(arg_wanted)

    def request_build_host(self):
        """ Request build host to use for build. """
        raise NotImplementedError

    def wait_on_build_host_initialization(self):
        """ Ensure build host is launched and ready to be used. """
        raise NotImplementedError

    def get_build_host_ip(self):
        """ Get IP address associated with this dispatched build host. """
        raise NotImplementedError

    def release_build_host(self):
        """ Release the build host. """
        raise NotImplementedError

class IPAddrBuildHostDispatcher(BuildHostDispatcher):
    """ Dispatcher class that uses the IP address given as the build host. """

    def __init__(self, build_config, arg_dict):
        """ Initialization function. Sets IP address and determines if it is localhost.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        BuildHostDispatcher.__init__(self, build_config, arg_dict)

        # ip addr associated with build host
        self.ip_addr = None

        rootLogger.info("Using host {} for {}".format(self.build_config.build_host, self.build_config.get_chisel_triplet()))

    def parse_args(self):
        """ Parse build host arguments. """
        # get default arguments
        BuildHostDispatcher.parse_args(self)

        # ip address arg
        self.ip_addr = self.get_arg("ipaddr")
        if self.ip_addr == "localhost":
            self.is_local = True

    def request_build_host(self):
        """ In this case, nothing happens since IP address should be pre-setup. """
        return

    def wait_on_build_host_initialization(self):
        """ In this case, nothing happens since IP address should be pre-setup. """
        return

    def get_build_host_ip(self):
        """ Get IP address associated with this dispatched build host.

        Returns:
            (str): IP address given as the dispatcher arg
        """

        return self.ip_addr

    def release_build_host(self):
        """ In this case, nothing happens. Up to the IP address to cleanup after itself. """
        return

class EC2BuildHostDispatcher(BuildHostDispatcher):
    """ Dispatcher class to manage an AWS EC2 instance as the build host. """

    def __init__(self, build_config, arg_dict):
        """ Initialization function. Setup AWS instance variables.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        BuildHostDispatcher.__init__(self, build_config, arg_dict)

        # instance object associated with the build host
        self.launched_instance_object = None

        # aws specific options
        self.instance_type = None
        self.build_instance_market = None
        self.spot_interruption_behavior = None
        self.spot_max_price = None

        # ec2 hosts will never be local
        self.is_local = False

    def parse_args(self):
        """ Parse build host arguments. """
        # get default arguments
        BuildHostDispatcher.parse_args(self)

        # get aws specific args
        self.instance_type = self.get_arg('instancetype')
        self.build_instance_market = self.get_arg('buildinstancemarket')
        self.spot_interruption_behavior = self.get_arg('spotinterruptionbehavior')
        self.spot_max_price = self.get_arg('spotmaxprice')

    def request_build_host(self):
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

    def wait_on_build_host_initialization(self):
        """ Wait for EC2 instance launch. """
        wait_on_build_host_initializationes([self.launched_instance_object])

    def get_build_host_ip(self):
        """ Get IP address associated with this dispatched instance.

        Returns:
            (str): IP address of EC2 build host
        """

        return self.launched_instance_object.private_ip_address

    def release_build_host(self):
        """ Terminate the EC2 instance running this build. """
        instance_ids = get_instance_ids_for_instances([self.launched_instance_object])
        rootLogger.info("Terminating build instances {}. Please confirm in your AWS Management Console".format(instance_ids))
        terminate_instances(instance_ids, dryrun=False)
