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

# imports needed for python type checking
from typing import Dict, Optional, List, Set, TYPE_CHECKING
from argparse import Namespace

rootLogger = logging.getLogger()

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
    """
    args: Namespace
    agfistoshare: List[str]
    acctids_to_sharewith: List[str]
    hwdb: RuntimeHWDB
    builds_list: List[BuildConfig]
    build_ip_set: Set[str]
    num_builds: int

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

        build_farm_hosts_config_file = None
        with open(args.buildfarmconfigfile, "r") as yaml_file:
            build_farm_hosts_config_file = yaml.safe_load(yaml_file)

        build_recipes = dict()
        for section_name, section_dict in build_recipes_config_file.items():
            if section_name in builds_to_run_list:
                build_recipes[section_name] = BuildConfig(
                    section_name,
                    section_dict,
                    build_farm_hosts_config_file,
                    self,
                    launch_time)

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(build_recipes.values())
        self.build_ip_set = set()

    def setup(self) -> None:
        """Setup based on the types of build farm hosts."""
        for build in self.builds_list:
            auto_create_bucket(build.s3_bucketname)

        # check to see email notifications can be subscribed
        get_snsname_arn()

    def request_build_farm_hosts(self) -> None:
        """Launch an instance for the builds. Exits the program if an IP address is reused."""
        for build in self.builds_list:
            build.build_farm_host_dispatcher.request_build_farm_host()
            num_ips = len(self.build_ip_set)
            ip = build.build_farm_host_dispatcher.get_build_farm_host_ip()
            self.build_ip_set.add(ip)
            if num_ips == len(self.build_ip_set):
                rootLogger.critical(f"ERROR: Duplicate {ip} IP used when launching instance")
                self.release_build_farm_hosts()
                sys.exit(1)

    def wait_on_build_farm_host_initializations(self) -> None:
        """Block until all build instances are initialized."""
        for build in self.builds_list:
            build.build_farm_host_dispatcher.wait_on_build_farm_host_initialization()

    def release_build_farm_hosts(self) -> None:
        """Terminate all build instances that are launched."""
        for build in self.builds_list:
            build.build_farm_host_dispatcher.release_build_farm_host()

    def get_build_by_ip(self, nodeip: str) -> Optional[BuildConfig]:
        """Obtain the build config For a particular IP address.

        Args:
            nodeip: IP address of build config wanted

        Returns:
            BuildConfig for `nodeip`. Returns `None` if `nodeip` is not found.
        """
        for build in self.builds_list:
            if build.build_farm_host_dispatcher.get_build_farm_host_ip() == nodeip:
                return build
        return None

    def __str__(self) -> str:
        """Print the class.

        Returns:
            String representation of the class.
        """
        return pprint.pformat(vars(self))
