from __future__ import annotations
from enum import Enum, auto
import sys
import logging

from time import strftime, gmtime
import pprint
import yaml

from awstools.awstools import valid_aws_configure_creds, aws_resource_names
from buildtools.bitbuilder import BitBuilder
import buildtools
from util.deepmerge import deep_merge

# imports needed for python type checking
from typing import Set, Any, Optional, Dict, TYPE_CHECKING
if TYPE_CHECKING:
    from buildtools.buildconfigfile import BuildConfigFile

rootLogger = logging.getLogger()

class InvalidBuildConfigSetting(Exception):
    pass

# All known strategy strings. A given platform may not implement all of them.
class BuildStrategy(Enum):
    BASIC      = auto()
    AREA       = auto()
    TIMING     = auto()
    EXPLORE    = auto()
    CONGESTION = auto()
    NORETIMING = auto()
    DEFAULT    = auto()

    @staticmethod
    def from_string(input: str) -> BuildStrategy:
        """ Constructs an instance of this enum from an input string """
        try:
            return BuildStrategy[input]
        except KeyError:
            all_names = [name for name, _ in BuildStrategy.__members__.items()]
            raise InvalidBuildConfigSetting(f"Invalid buildstrategy name '{input}'. \n Valid options: {all_names}")



class BuildConfig:
    """Represents a single build configuration used to build RTL, drivers, and bitstreams.

    Attributes:
        name: Name of config i.e. name of `config_build_recipe.yaml` section.
        build_config_file: Pointer to global build config file.
        TARGET_PROJECT: Target project to build.
        DESIGN: Design to build.
        TARGET_CONFIG: Target config to build.
        deploy_quintuplet: Deploy quintuplet override.
        launch_time: Launch time of the manager.
        PLATFORM_CONFIG: Platform config to build.
        fpga_frequency: Frequency for the FPGA build.
        strategy: Strategy for the FPGA build.
        post_build_hook: Post build hook script.
        bitbuilder: bitstream configuration class.
    """
    name: str
    build_config_file: BuildConfigFile
    TARGET_PROJECT: str
    DESIGN: str
    TARGET_CONFIG: str
    deploy_quintuplet: Optional[str]
    frequency: float
    strategy: BuildStrategy
    launch_time: str
    PLATFORM_CONFIG: str
    post_build_hook: str
    bitbuilder: BitBuilder

    def __init__(self,
            name: str,
            recipe_config_dict: Dict[str, Any],
            build_config_file: BuildConfigFile,
            launch_time: str) -> None:
        """
        Args:
            name: Name of config i.e. name of `config_build_recipe.yaml` section.
            recipe_config_dict: `config_build_recipe.yaml` options associated with name.
            build_config_file: Global build config file.
            launch_time: Time manager was launched.
        """
        self.name = name
        self.build_config_file = build_config_file

        # default provided for old build recipes that don't specify TARGET_PROJECT, PLATFORM
        self.PLATFORM = recipe_config_dict.get('PLATFORM', 'f1')
        self.TARGET_PROJECT = recipe_config_dict.get('TARGET_PROJECT', 'firesim')
        self.DESIGN = recipe_config_dict['DESIGN']
        self.TARGET_CONFIG = recipe_config_dict['TARGET_CONFIG']

        if 'deploy_triplet' in recipe_config_dict.keys() and 'deploy_quintuplet' in recipe_config_dict.keys():
            rootLogger.error("Cannot have both 'deploy_quintuplet' and 'deploy_triplet' in build config. Define only 'deploy_quintuplet'.")
            sys.exit(1)
        elif 'deploy_triplet' in recipe_config_dict.keys():
            rootLogger.warning("Please rename your 'deploy_triplet' key in your build config to 'deploy_quintuplet'. Support for 'deploy_triplet' will be removed in the future.")

        self.deploy_quintuplet = recipe_config_dict.get('deploy_quintuplet')
        if self.deploy_quintuplet is None:
            # temporarily support backwards compat
            self.deploy_quintuplet = recipe_config_dict.get('deploy_triplet')

        if self.deploy_quintuplet is not None and len(self.deploy_quintuplet.split("-")) == 3:
            self.deploy_quintuplet = 'f1-firesim-' + self.deploy_quintuplet
        self.launch_time = launch_time

        # run platform specific options
        self.PLATFORM_CONFIG = recipe_config_dict['PLATFORM_CONFIG']
        self.post_build_hook = recipe_config_dict['post_build_hook']

        # retrieve frequency and strategy selections
        bitstream_build_args = recipe_config_dict['platform_config_args']
        self.fpga_frequency = bitstream_build_args['fpga_frequency']
        self.build_strategy = BuildStrategy.from_string(bitstream_build_args['build_strategy'])

        # retrieve the bitbuilder section
        bitbuilder_conf_dict = None
        with open(recipe_config_dict['bit_builder_recipe'], "r") as yaml_file:
            bitbuilder_conf_dict = yaml.safe_load(yaml_file)

        bitbuilder_type_name = bitbuilder_conf_dict["bit_builder_type"]
        bitbuilder_args = bitbuilder_conf_dict["args"]

        # add the overrides if it exists
        override_args = recipe_config_dict.get('bit_builder_arg_overrides')
        if override_args:
            bitbuilder_args = deep_merge(bitbuilder_args, override_args)

        bitbuilder_dispatch_dict = dict([(x.__name__, x) for x in buildtools.buildconfigfile.inheritors(BitBuilder)])

        if not bitbuilder_type_name in bitbuilder_dispatch_dict:
            raise Exception(f"Unable to find {bitbuilder_type_name} in available bitbuilder classes: {bitbuilder_dispatch_dict.keys()}")

        # validate the frequency
        if (self.fpga_frequency is None) or not (0 < self.fpga_frequency <= 300.0):
            raise Exception(f"{self.fpga_frequency} is not a valid build frequency. Valid frequencies are between 0.0-300.0 (MHz)")

        # create dispatcher object using class given and pass args to it
        self.bitbuilder = bitbuilder_dispatch_dict[bitbuilder_type_name](self, bitbuilder_args)

    def get_chisel_triplet(self) -> str:
        """Get the unique build-specific '-' deliminated triplet.

        Returns:
            Chisel triplet
        """
        return f"{self.DESIGN}-{self.TARGET_CONFIG}-{self.PLATFORM_CONFIG}"

    def get_effective_deploy_triplet(self) -> str:
        """Get the effective deploy triplet, i.e. the triplet version of
        get_effective_deploy_quadruplet().

        Returns:
            Effective deploy triplet
        """
        return "-".join(self.get_effective_deploy_quintuplet().split("-")[2:])

    def get_chisel_quintuplet(self) -> str:
        """Get the unique build-specific '-' deliminated quintuplet.

        Returns:
            Chisel quintuplet
        """
        return f"{self.PLATFORM}-{self.TARGET_PROJECT}-{self.DESIGN}-{self.TARGET_CONFIG}-{self.PLATFORM_CONFIG}"

    def get_effective_deploy_quintuplet(self) -> str:
        """Get the effective deploy quintuplet, i.e. the value specified in
        deploy_quintuplet if specified, otherwise just get_chisel_quintuplet().

        Returns:
            Effective deploy quintuplet
        """
        if self.deploy_quintuplet:
            return self.deploy_quintuplet
        return self.get_chisel_quintuplet()

    def get_frequency(self) -> float:
        """Get the desired fpga frequency.

        Returns:
            Specified FPGA frequency (float)
        """
        return self.fpga_frequency

    def get_strategy(self) -> BuildStrategy:
        """Get the strategy string.

        Returns:
            Specified build strategy
        """
        return self.build_strategy

    def get_build_dir_name(self) -> str:
        """Get the name of the local build directory.

        Returns:
            Name of local build directory (based on time/name).
        """
        return f"{self.launch_time}-{self.name}"

    def make_recipe(self, recipe: str) -> str:
        """Create make command for a given recipe using the tuple variables.

        Args:
            recipe: Make variables/target to run.

        Returns:
            Fully specified make command.
        """
        return f"""make PLATFORM={self.PLATFORM} TARGET_PROJECT={self.TARGET_PROJECT} DESIGN={self.DESIGN} TARGET_CONFIG={self.TARGET_CONFIG} PLATFORM_CONFIG={self.PLATFORM_CONFIG} {recipe}"""

    def __repr__(self) -> str:
        return f"< {type(self)}(name={self.name!r}, build_config_file={self.build_config_file!r}) @{id(self)} >"

    def __str__(self) -> str:
        return pprint.pformat(vars(self), width=1, indent=10)
