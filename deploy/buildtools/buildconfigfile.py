from time import strftime, gmtime
import pprint
import logging
import sys
import yaml
from collections import defaultdict
from importlib import import_module

from runtools.runtime_config import RuntimeHWDB
from buildtools.buildconfig import BuildConfig
from awstools.awstools import auto_create_bucket, get_snsname_arn
from buildtools.buildfarm import BuildFarm

# imports needed for python type checking
from typing import Dict, Optional, List, Set, Type, Any, TYPE_CHECKING
from argparse import Namespace

rootLogger = logging.getLogger()

def inheritors(klass: Type[Any]) -> Set[Type[Any]]:
    """Determine the subclasses that inherit from the input class.
    This is taken from https://stackoverflow.com/questions/5881873/python-find-all-classes-which-inherit-from-this-one.

    Args:
        klass: Input class.

    Returns:
        Set of subclasses that inherit from input class.
    """
    subclasses = set()
    work = [klass]
    while work:
        parent = work.pop()
        for child in parent.__subclasses__():
            if child not in subclasses:
                subclasses.add(child)
                work.append(child)
    return subclasses

class BuildConfigFile:
    """Class representing the "global" build config file i.e. `config_build.yaml`.

    Attributes:
        args: Args passed by the top-level manager argparse.
        agfistoshare: List of build recipe names (associated w/ AGFIs) to share.
        acctids_to_sharewith: List of AWS account names to share AGFIs with.
        hwdb: Object holding all HWDB entries.
        builds_list: List of build recipe names to build.
        build_ip_set: List of IPs to use for builds.
        num_builds: Number of builds to run.
        build_farm: Build farm used to host builds.
    """
    args: Namespace
    agfistoshare: List[str]
    acctids_to_sharewith: List[str]
    hwdb: RuntimeHWDB
    builds_list: List[BuildConfig]
    build_ip_set: Set[str]
    num_builds: int
    build_farm: BuildFarm

    def __init__(self, args: Namespace) -> None:
        """
        Args:
            args: Object holding arg attributes.
        """
        if args.launchtime:
            launch_time = args.launchtime
        else:
            launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

        global_build_config_file = None
        with open(args.buildconfigfile, "r") as yaml_file:
            global_build_config_file = yaml.safe_load(yaml_file)

        # aws specific options
        self.agfistoshare = global_build_config_file['agfis-to-share']
        self.acctids_to_sharewith = global_build_config_file['share-with-accounts'].values()

        # this is a list of actual builds to run
        builds_to_run_list = global_build_config_file['builds']
        self.num_builds = len(builds_to_run_list)

        build_recipes_config_file = None
        with open(args.buildrecipesconfigfile, "r") as yaml_file:
            build_recipes_config_file = yaml.safe_load(yaml_file)

        build_recipes = dict()
        for section_name, section_dict in build_recipes_config_file.items():
            if section_name in builds_to_run_list:
                build_recipes[section_name] = BuildConfig(
                    section_name,
                    section_dict,
                    self,
                    launch_time)

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(build_recipes.values())
        self.build_ip_set = set()

        # retrieve the build host section
        build_farm_config_file = None
        with open(args.buildfarmconfigfile, "r") as yaml_file:
            build_farm_config_file = yaml.safe_load(yaml_file)

        build_farm_name = global_build_config_file["default-build-farm"]
        build_farm_conf_dict = build_farm_config_file[build_farm_name]

        build_farm_type_name = build_farm_conf_dict["build-farm-type"]
        build_farm_args = build_farm_conf_dict["args"]

        build_farm_dispatch_dict = dict([(x.NAME(), x) for x in inheritors(BuildFarm)])

        # create dispatcher object using class given and pass args to it
        self.build_farm = build_farm_dispatch_dict[build_farm_type_name](self, build_farm_args)

    def setup(self) -> None:
        """Setup based on the types of build hosts."""
        for build in self.builds_list:
            auto_create_bucket(build.s3_bucketname)

        # check to see email notifications can be subscribed
        get_snsname_arn()

    def request_build_hosts(self) -> None:
        """Launch an instance for the builds. Exits the program if an IP address is reused."""
        for build in self.builds_list:
            self.build_farm.request_build_host(build)
            ip = self.build_farm.get_build_host_ip(build)
            if ip in self.build_ip_set:
                error_msg = f"ERROR: Duplicate {ip} IP used when launching instance."
                rootLogger.critical(error_msg)
                self.release_build_hosts()
                raise Exception(error_msg)
            else:
                self.build_ip_set.add(ip)

    def wait_on_build_host_initializations(self) -> None:
        """Block until all build instances are initialized."""
        for build in self.builds_list:
            self.build_farm.wait_on_build_host_initialization(build)

    def release_build_hosts(self) -> None:
        """Terminate all build instances that are launched."""
        for build in self.builds_list:
            self.build_farm.release_build_host(build)

    def get_build_by_ip(self, nodeip: str) -> Optional[BuildConfig]:
        """Obtain the build config for a particular IP address.

        Args:
            nodeip: IP address of build config wanted

        Returns:
            BuildConfig for `nodeip`. Returns `None` if `nodeip` is not found.
        """
        for build in self.builds_list:
            if self.build_farm.get_build_host_ip(build) == nodeip:
                return build
        return None

    def __str__(self) -> str:
        """Print the class.

        Returns:
            String representation of the class.
        """
        return pprint.pformat(vars(self))
