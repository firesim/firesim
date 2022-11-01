from __future__ import annotations

from time import strftime, gmtime
import pprint
import logging
import sys
import yaml

from runtools.runtime_config import RuntimeHWDB
from buildtools.buildconfig import BuildConfig
from buildtools.buildfarm import BuildFarm
from util.inheritors import inheritors
from util.deepmerge import deep_merge

# imports needed for python type checking
from typing import Dict, Optional, List, Set, Type, Any, TYPE_CHECKING
import argparse # this is not within a if TYPE_CHECKING: scope so the `register_task` in FireSim can evaluate it's annotation

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
        build_farm: Build farm used to host builds.
    """
    args: argparse.Namespace
    forceterminate: bool
    agfistoshare: List[str]
    acctids_to_sharewith: List[str]
    hwdb: RuntimeHWDB
    builds_list: List[BuildConfig]
    build_ip_set: Set[str]
    num_builds: int
    build_farm: BuildFarm

    def __init__(self, args: argparse.Namespace) -> None:
        """
        Args:
            args: Object holding arg attributes.
        """
        if args.launchtime:
            launch_time = args.launchtime
        else:
            launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

        self.forceterminate = args.forceterminate

        global_build_config_file = None
        with open(args.buildconfigfile, "r") as yaml_file:
            global_build_config_file = yaml.safe_load(yaml_file)

        # aws specific options
        self.agfistoshare = global_build_config_file['agfis_to_share']
        self.acctids_to_sharewith = global_build_config_file['share_with_accounts'].values()

        # this is a list of actual builds to run
        builds_to_run_list = global_build_config_file['builds_to_run']
        self.num_builds = len(builds_to_run_list)

        build_recipes_config_file = None
        with open(args.buildrecipesconfigfile, "r") as yaml_file:
            build_recipes_config_file = yaml.safe_load(yaml_file)

        build_recipes = dict()
        for section_name, section_dict in build_recipes_config_file.items():
            if section_name in builds_to_run_list:
                try:
                    build_recipes[section_name] = BuildConfig(
                        section_name,
                        section_dict,
                        self,
                        launch_time)
                except Exception as e:
                    raise Exception(f"Error constructing build recipe '{section_name}'") from e

        self.hwdb = RuntimeHWDB(args.hwdbconfigfile)

        self.builds_list = list(build_recipes.values())
        self.build_ip_set = set()

        # retrieve the build host section

        build_farm_defaults_file = global_build_config_file["build_farm"]["base_recipe"]
        build_farm_config_file = None
        with open(build_farm_defaults_file, "r") as yaml_file:
            build_farm_config_file = yaml.safe_load(yaml_file)

        build_farm_type_name = build_farm_config_file["build_farm_type"]
        build_farm_args = build_farm_config_file["args"]

        # add the overrides if it exists
        override_args = global_build_config_file['build_farm'].get('recipe_arg_overrides')
        if override_args:
            build_farm_args = deep_merge(build_farm_args, override_args)

        build_farm_dispatch_dict = dict([(x.__name__, x) for x in inheritors(BuildFarm)])

        if not build_farm_type_name in build_farm_dispatch_dict:
            raise Exception(f"Unable to find {build_farm_type_name} in available build farm classes: {build_farm_dispatch_dict.keys()}")

        # create dispatcher object using class given and pass args to it
        self.build_farm = build_farm_dispatch_dict[build_farm_type_name](build_farm_args)

        # do bitbuilder setup after all parsing is complete
        for build in self.builds_list:
            build.bitbuilder.setup()

    def request_build_hosts(self) -> None:
        """Launch an instance for the builds. Exits the program if an IP address is reused."""
        for build in self.builds_list:
            self.build_farm.request_build_host(build)

    def wait_on_build_host_initializations(self) -> None:
        """Block until all build instances are initialized."""
        for build in self.builds_list:
            self.build_farm.wait_on_build_host_initialization(build)

            ip = self.build_farm.get_build_host_ip(build)
            if ip in self.build_ip_set:
                error_msg = f"ERROR: Duplicate {ip} IP used when launching instance."
                rootLogger.critical(error_msg)
                self.release_build_hosts()
                raise Exception(error_msg)
            else:
                self.build_ip_set.add(ip)


    def release_build_hosts(self) -> None:
        """Terminate all build instances that are launched."""
        for build in self.builds_list:
            self.build_farm.release_build_host(build)

    def get_build_by_ip(self, nodeip: str) -> BuildConfig:
        """Obtain the build config for a particular IP address.

        Args:
            nodeip: IP address of build config wanted

        Returns:
            BuildConfig for `nodeip`. Returns `None` if `nodeip` is not found.
        """
        for build in self.builds_list:
            if self.build_farm.get_build_host_ip(build) == nodeip:
                return build
        assert False, f"Unable to find build config associated with {nodeip}"

    def __repr__(self) -> str:
        return f"< {type(self)}(file={self.args.buildconfigfile!r}, recipes={self.args.buildrecipesconfigfile!r}, build_farm={self.build_farm!r}) @{id(self)} >"

    def __str__(self) -> str:
        return pprint.pformat(vars(self), width=1, indent=10)
