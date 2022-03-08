import logging
import sys

from awstools.awstools import *

# imports needed for python type checking
from typing import Any, Dict, Optional, List, TYPE_CHECKING
from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
# needed to avoid type-hint circular dependencies
# TODO: Solved in 3.7.+ by "from __future__ import annotations" (see https://stackoverflow.com/questions/33837918/type-hints-solve-circular-dependency)
#       and normal "import <module> as ..." syntax (see https://www.reddit.com/r/Python/comments/cug90e/how_to_not_create_circular_dependencies_when/)
if TYPE_CHECKING:
    from buildtools.buildconfig import BuildConfig
else:
    BuildConfig = object

rootLogger = logging.getLogger()

class BuildFarmHostDispatcher(object):
    """Abstract class to manage how to handle a single build farm host (request, wait, release, etc).

    Attributes:
        NAME: Human-readable name (used as the "build-farm-type" in the YAML).
        build_config: Build config associated with the dispatcher.
        args: Set of args/options associated with the dispatcher.
        dest_build_dir: Name of build dir on build host.
    """
    NAME: str = ""
    build_config: BuildConfig
    args: Dict[str, Any]
    dest_build_dir: str

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        """
        Args:
            build_config: Build config associated with this dispatcher.
            args: Args (i.e. options) passed to the dispatcher.
        """
        self.build_config = build_config
        self.args = args
        self.dest_build_dir = ""

    def parse_args(self) -> None:
        """Parse default build farm arguments."""
        raise NotImplementedError

    def request_build_farm_host(self) -> None:
        """Request build farm host to use for build."""
        raise NotImplementedError

    def wait_on_build_farm_host_initialization(self) -> None:
        """Ensure build farm host is launched and ready to be used."""
        raise NotImplementedError

    def get_build_farm_host_ip(self) -> str:
        """Get IP address associated with this dispatched build farm host.

        Returns:
            IP address for the specific build host.
        """
        raise NotImplementedError

    def release_build_farm_host(self) -> None:
        """Release the build farm host."""
        raise NotImplementedError

class IPAddrBuildFarmHostDispatcher(BuildFarmHostDispatcher):
    """Dispatcher class that selects from a set of user-determined IPs to allocate a new build farm host.

    Attributes:
        dispatch_counter: Counter to track number of hosts launched.
        ip_addr: IP address associated with build farm host.
        dispatch_id: Index into list of user-provided IPs (determined from `dispatch_counter`).
    """
    NAME: str = "unmanaged"
    dispatch_counter: int = 0
    ip_addr: str
    dispatch_id: int

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        """Initialization function. Sets IP address and determines if it is localhost.

        Args:
            build_config: Build config associated with this dispatcher.
            args: Args (i.e. options) passed to the dispatcher.
        """
        BuildFarmHostDispatcher.__init__(self, build_config, args)

        self.ip_addr = ""

        self.dispatch_id = IPAddrBuildFarmHostDispatcher.dispatch_counter
        IPAddrBuildFarmHostDispatcher.dispatch_counter += 1

    def parse_args(self) -> None:
        """Parse build farm host arguments."""
        build_farm_hosts_key = "build-farm-hosts"
        build_farm_hosts_list = self.args[build_farm_hosts_key]
        if len(build_farm_hosts_list) > self.dispatch_id:
            default_build_dir = self.args["default-build-dir"]

            build_farm_host = build_farm_hosts_list[self.dispatch_id]

            if type(build_farm_host) is dict:
                # add element { ip-addr: { arg1: val1, arg2: val2, ... } }
                assert(len(build_farm_host.keys()) == 1)

                self.ip_addr = list(build_farm_host.keys())[0]
                ip_args = list(build_farm_host.values())[0]

                self.dest_build_dir = ip_args.get("override-build-dir", default_build_dir)
            elif type(build_farm_host) is str:
                # add element w/ defaults

                self.ip_addr = build_farm_host
                self.dest_build_dir = default_build_dir
            else:
                raise Exception(f"""Unexpected yaml type provided in "{build_farm_hosts_key}" list. Must be dict or str.""")

            rootLogger.info(f"Using host {self.build_config.build_farm_host_name} for {self.build_config.get_chisel_triplet()} with IP address: {self.ip_addr}")
        else:
            error_msg = f"ERROR: Fewer IPs available than builds. {self.build_config.build_config_file.num_builds} IPs requested but got {len(build_farm_hosts_list)} IPs"
            rootLogger.critical(error_msg)
            raise Exception(error_msg)

    def request_build_farm_host(self) -> None:
        """Nothing happens since the provided IP address is already granted by something outside FireSim."""
        return

    def wait_on_build_farm_host_initialization(self) -> None:
        """Nothing happens since the provided IP address is already granted by something outside FireSim."""
        return

    def get_build_farm_host_ip(self) -> str:
        """Get IP address associated with this dispatched build farm host.

        Returns:
            IP address given as part of the dispatcher args.
        """
        return self.ip_addr

    def release_build_farm_host(self) -> None:
        """ Nothing happens. Up to the IP address provider to cleanup after itself."""
        return

class EC2BuildFarmHostDispatcher(BuildFarmHostDispatcher):
    """Dispatcher class to manage an AWS EC2 instance as the build farm host.

    Attributes:
        launched_instance_object: instance object associated with the build farm host.
        instance_type: instance object type
        build_instance_market: instance market type
        spot_interruption_behavior: if spot instance, the interruption behavior
        spot_max_price: if spot instance, the max price
    """
    NAME: str = "aws-ec2"
    launched_instance_object: EC2InstanceResource
    instance_type: str
    build_instance_market: str
    spot_interruption_behavior: str
    spot_max_price: str

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        """Initialization function. Sets AWS instance variables.

        Args:
            build_config: Build config associated with this dispatcher.
            args: Args (i.e. options) passed to the dispatcher.
        """
        BuildFarmHostDispatcher.__init__(self, build_config, args)

        self.launched_instance_object = None # type: ignore
        self.instance_type = ""
        self.build_instance_market = ""
        self.spot_interruption_behavior = ""
        self.spot_max_price = ""

    def parse_args(self) -> None:
        """Parse build farm host arguments."""
        # get aws specific args
        self.instance_type = self.args['instance-type']
        self.build_instance_market = self.args['build-instance-market']
        self.spot_interruption_behavior = self.args['spot-interruption-behavior']
        self.spot_max_price = self.args['spot-max-price']

        self.dest_build_dir = self.args["build-dir"]

    def request_build_farm_host(self) -> None:
        """Launch an AWS EC2 instance for the build config."""
        # get access to the runfarmprefix, which we will apply to build
        # instances too now.
        aws_resource_names_dict = aws_resource_names()
        # just duplicate the runfarmprefix for now. This can be None,
        # in which case we give an empty build farm prefix
        build_farm_prefix = aws_resource_names_dict['runfarmprefix']

        buildfarmprefix = '' if build_farm_prefix is None else build_farm_prefix

        self.launched_instance_object = launch_instances(
            self.instance_type,
            1,
            self.build_instance_market,
            self.spot_interruption_behavior,
            self.spot_max_price,
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
            randomsubnet=True)

    def wait_on_build_farm_host_initialization(self) -> None:
        """Wait for EC2 instance launch."""
        wait_on_instance_launches([self.launched_instance_object])

    def get_build_farm_host_ip(self) -> str:
        """Get private IP address associated with this dispatched instance.

        Returns:
            IP address given as part of the dispatcher args.
        """
        return self.launched_instance_object.private_ip_address

    def release_build_farm_host(self) -> None:
        """ Terminate the EC2 instance running this build. """
        instance_ids = get_instance_ids_for_instances([self.launched_instance_object])
        rootLogger.info(f"Terminating build instances {instance_ids}. Please confirm in your AWS Management Console")
        terminate_instances(instance_ids, dryrun=False)
