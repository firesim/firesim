from __future__ import annotations

from time import strftime, gmtime
import pprint
import yaml
import os
import logging

from awstools.awstools import valid_aws_configure_creds, aws_resource_names
from buildtools.bitbuilder import BitBuilder
from util.deepmerge import deep_merge
from util.configvalidation import validate
from util.inheritors import inheritors

# imports needed for python type checking
from typing import Set, Any, Optional, Dict, TYPE_CHECKING
if TYPE_CHECKING:
    from buildtools.buildconfigfile import BuildConfigFile

rootLogger = logging.getLogger()

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
        post_build_hook: Post build hook script.
        bitbuilder: bitstream configuration class.
    """
    name: str
    build_config_file: BuildConfigFile
    TARGET_PROJECT: Optional[str]
    DESIGN: str
    TARGET_CONFIG: str
    deploytriplet: Optional[str]
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

        bit_builder_recipe_file = recipe_config_dict['bit_builder_recipe']
        schema_file = f"schemas/{bit_builder_recipe_file}"

        def validate_helper(val_cond: bool) -> None:
            if os.path.exists(schema_file):
                if not val_cond:
                    raise Exception(f"Invalid YAML in build recipe: {name}")
            else:
                rootLogger.warning(f"Unable to find schema file for {bit_builder_recipe_file}. Skipping validation.")

        validate_helper(validate(bit_builder_recipe_file, None, schema_file))

        # retrieve the bitbuilder section
        bitbuilder_conf_dict = None
        with open(bit_builder_recipe_file, "r") as yaml_file:
            bitbuilder_conf_dict = yaml.safe_load(yaml_file)

        bitbuilder_type_name = bitbuilder_conf_dict["bit_builder_type"]
        bitbuilder_args = bitbuilder_conf_dict["args"]

        # add the overrides if it exists
        override_args = recipe_config_dict.get('bit_builder_arg_overrides')
        if override_args:
            # validate overrides
            override_bit_builder_arg_dict = {"bit_builder_type": bitbuilder_type_name, "args": override_args}
            validate_helper(validate(None, yaml.dump(override_bit_builder_arg_dict), schema_file, None, ["Required field missing"]))

            bitbuilder_args = deep_merge(bitbuilder_args, override_args)

        bitbuilder_dispatch_dict = dict([(x.__name__, x) for x in inheritors(BitBuilder)])

        if not bitbuilder_type_name in bitbuilder_dispatch_dict:
            raise Exception(f"Unable to find {bitbuilder_type_name} in available bitbuilder classes: {bitbuilder_dispatch_dict.keys()}")

        # create dispatcher object using class given and pass args to it
        self.bitbuilder = bitbuilder_dispatch_dict[bitbuilder_type_name](self, bitbuilder_args)

    def get_chisel_triplet(self) -> str:
        """Get the unique build-specific '-' deliminated triplet.

        Returns:
            Chisel triplet
        """
        return f"{self.DESIGN}-{self.TARGET_CONFIG}-{self.PLATFORM_CONFIG}"

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


