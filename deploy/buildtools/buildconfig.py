from __future__ import annotations
from enum import Enum, auto

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
        deploytriplet: Deploy triplet override.
        launch_time: Launch time of the manager.
        PLATFORM_CONFIG: Platform config to build.
        fpga_frequency: Frequency for the FPGA build.
        strategy: Strategy for the FPGA build.
        post_build_hook: Post build hook script.
        bitbuilder: bitstream configuration class.
    """
    name: str
    build_config_file: BuildConfigFile
    TARGET_PROJECT: Optional[str]
    DESIGN: str
    TARGET_CONFIG: str
    deploytriplet: Optional[str]
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

        self.TARGET_PROJECT = recipe_config_dict.get('TARGET_PROJECT')
        self.DESIGN = recipe_config_dict['DESIGN']
        self.TARGET_CONFIG = recipe_config_dict['TARGET_CONFIG']
        self.deploytriplet = recipe_config_dict['deploy_triplet']
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
        return f"""make {"" if self.TARGET_PROJECT is None else "TARGET_PROJECT=" + self.TARGET_PROJECT} DESIGN={self.DESIGN} TARGET_CONFIG={self.TARGET_CONFIG} PLATFORM_CONFIG={self.PLATFORM_CONFIG} {recipe}"""

    def __repr__(self) -> str:
        return f"< {type(self)}(name={self.name!r}, build_config_file={self.build_config_file!r}) @{id(self)} >"

    def __str__(self) -> str:
        return pprint.pformat(vars(self), width=1, indent=10)


