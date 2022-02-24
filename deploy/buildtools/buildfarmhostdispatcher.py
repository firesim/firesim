import logging
import sys

from awstools.awstools import *

# typing imports
from typing import Any, Dict, Optional, List, TYPE_CHECKING
from mypy_boto3_ec2.service_resource import Instance
# TODO: Solved by "from __future__ import annotations" (see https://stackoverflow.com/questions/33837918/type-hints-solve-circular-dependency)
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
        is_local: Is the dispatcher the same as the manager host?
        override_remote_build_dir: Override name of build dir on build host (default to $HOME).
    """
    NAME: str = ""
    build_config: BuildConfig
    args: Dict[str, Any]
    is_local: bool
    override_remote_build_dir: Optional[str]

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        """
        Args:
            build_config: Build config associated with this dispatcher.
            args: Args (i.e. options) passed to the dispatcher.
        """
        self.build_config = build_config
        self.args = args
        self.is_local = False
        self.override_remote_build_dir = None

    def parse_args(self) -> None:
        """Parse default build farm arguments."""
        raise NotImplementedError

    def request_build_farm_host(self) -> None:
        """Request build farm host to use for build."""
        raise NotImplementedError

    @staticmethod
    def request_build_farm_hosts(build_farm_host_dispatchers: List['BuildFarmHostDispatcher']) -> None:
        """Request multiple build farm hosts."""
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
    """Dispatcher class that uses a set of predetermined IPs as the build farm host.

    Attributes:
        dispatch_counter: Counter to track number of hosts launched.
        ip_addr: IP address associated with build farm hosts.
        dispatch_id: ID of build host (determined from `dispatch_counter`).
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
        build_farm_hosts_list = self.args["build-farm-hosts"]
        if len(build_farm_hosts_list) > self.dispatch_id:
            default_build_dir = self.args.get("default-build-dir")

            build_farm_host = build_farm_hosts_list[self.dispatch_id]

            if type(build_farm_host) is dict:
                # add element { ip-addr: { arg1: val1, arg2: val2, ... } }
                assert(len(build_farm_host.keys()) == 1)

                self.ip_addr = list(build_farm_host.keys())[0]
                ip_args = list(build_farm_host.values())[0]

                self.override_remote_build_dir = ip_args.get("override-build-dir", default_build_dir)
            elif type(build_farm_host) is str:
                # add element w/ defaults

                self.ip_addr = build_farm_host
                self.override_remote_build_dir = default_build_dir
            else:
                raise Exception("Unknown build farm host type")

            if self.ip_addr in ["localhost", "127.0.0.1"]:
                self.is_local = True

            rootLogger.info("Using host {} for {} with IP address: {}".format(self.build_config.build_farm_host, self.build_config.get_chisel_triplet(), self.ip_addr))
        else:
            rootLogger.critical("ERROR: Less IPs available than builds. Add more IPs.")
            raise Exception("ERROR: Less IPs available than builds. Add more IPs.")

    def request_build_farm_host(self) -> None:
        """Nothing happens since the provided IP address is already granted by something outside FireSim."""
        return

    @staticmethod
    def request_build_farm_hosts(build_farm_host_dispatchers: List['IPAddrBuildFarmHostDispatcher']) -> None: # type: ignore[override]
        """Nothing happens since the provided IP addresses are already granted by something outside FireSim."""
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
    launched_instance_object: Instance
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

        # ec2 hosts will never be local
        self.is_local = False

    def parse_args(self) -> None:
        """Parse build farm host arguments."""
        # get aws specific args
        self.instance_type = self.args['instance-type']
        self.build_instance_market = self.args['build-instance-market']
        self.spot_interruption_behavior = self.args['spot-interruption-behavior']
        self.spot_max_price = self.args['spot-max-price']

        self.override_remote_build_dir = self.args.get("build-dir")

    def request_build_farm_host(self) -> None:
        """Launch an AWS EC2 instance for the build config."""
        build_instance_market = self.build_instance_market
        spot_interruption_behavior = self.spot_interruption_behavior
        spot_max_price = self.spot_max_price

        num_instances = 1
        self.launched_instance_object = EC2BuildFarmHostDispatcher.ec2_launch_instances(
            self.instance_type,
            num_instances,
            build_instance_market,
            spot_interruption_behavior,
            spot_max_price)[0]

    @staticmethod
    def request_build_farm_hosts(build_farm_host_dispatchers: List['EC2BuildFarmHostDispatcher']) -> None: # type: ignore[override]
        """Launch multiple AWS EC2 instances for the build configs."""
        # TODO: this can be further optimized (spawn-like build farm hosts)
        # double-check that build farms are the same
        it_len = len(set([x.instance_type for x in build_farm_host_dispatchers]))
        bim_len = len(set([x.build_instance_market for x in build_farm_host_dispatchers]))
        sib_len = len(set([x.spot_interruption_behavior for x in build_farm_host_dispatchers]))
        smp_len = len(set([x.spot_max_price for x in build_farm_host_dispatchers]))
        if it_len == 1 and bim_len == 1 and sib_len == 1 and smp_len == 1:
            amt_requested = len(build_farm_host_dispatchers)
            instance_type = build_farm_host_dispatchers[0].instance_type
            build_instance_market = build_farm_host_dispatchers[0].build_instance_market
            spot_interruption_behavior = build_farm_host_dispatchers[0].spot_interruption_behavior
            spot_max_price = build_farm_host_dispatchers[0].spot_max_price

            launched_objs = EC2BuildFarmHostDispatcher.ec2_launch_instances(
                instance_type,
                amt_requested,
                build_instance_market,
                spot_interruption_behavior,
                spot_max_price)

            for bh, lo in zip(build_farm_host_dispatchers, launched_objs):
                bh.launched_instance_object = lo
        else:
            # default to the original request function
            for build_farm_host_dispatcher in build_farm_host_dispatchers:
                 build_farm_host_dispatcher.request_build_farm_host()

    @staticmethod
    def ec2_launch_instances(inst_type: str, num_insts: int, build_inst_market: str, spot_int_behav: str, spot_max_price: str) -> List[Instance]:
        """Launch EC2 instances based on arguments.

        Args:
            inst_type: instance object type
            num_insts: number of instances to launch
            build_inst_market: instance market type
            spot_int_behav: if spot instance, the interruption behavior
            spot_max_price: if spot instance, the max price
        """
        # get access to the runfarmprefix, which we will apply to build
        # instances too now.
        aws_resource_names_dict = aws_resource_names()
        # just duplicate the runfarmprefix for now. This can be None,
        # in which case we give an empty build farm prefix
        build_farm_prefix = aws_resource_names_dict['runfarmprefix']

        buildfarmprefix = '' if build_farm_prefix is None else build_farm_prefix

        return launch_instances(
            inst_type,
            num_insts,
            build_inst_market,
            spot_int_behav,
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
        rootLogger.info("Terminating build instances {}. Please confirm in your AWS Management Console".format(instance_ids))
        terminate_instances(instance_ids, dryrun=False)
