from __future__ import with_statement, annotations

import abc
import yaml
import json
import time
import random
import string
import logging
import os
from fabric.api import prefix, local, run, env, lcd, parallel # type: ignore
from fabric.contrib.console import confirm # type: ignore
from fabric.contrib.project import rsync_project # type: ignore

from awstools.afitools import firesim_tags_to_description, copy_afi_to_all_regions
from awstools.awstools import send_firesim_notification, get_aws_userid, get_aws_region, auto_create_bucket, valid_aws_configure_creds, aws_resource_names, get_snsname_arn
from util.streamlogger import StreamLogger, InfoStreamLogger

# imports needed for python type checking
from typing import Optional, Dict, Any, TYPE_CHECKING
if TYPE_CHECKING:
    from buildtools.buildconfig import BuildConfig

rootLogger = logging.getLogger()

def get_deploy_dir() -> str:
    """Determine where the firesim/deploy directory is and return its path.

    Returns:
        Path to firesim/deploy directory.
    """
    with StreamLogger('stdout'), StreamLogger('stderr'):
        deploydir = local("pwd", capture=True)
    return deploydir

class BitBuilder(metaclass=abc.ABCMeta):
    """Abstract class to manage how to build a bitstream for a build config.

    Attributes:
        build_config: Build config to build a bitstream for.
        args: Args (i.e. options) passed to the bitbuilder.
    """
    build_config: BuildConfig
    args: Dict[str, Any]

    def __init__(self, build_config: BuildConfig, args: Dict[str, Any]) -> None:
        """
        Args:
            build_config: Build config to build a bitstream for.
            args: Args (i.e. options) passed to the bitbuilder.
        """
        self.build_config = build_config
        self.args = args

    @abc.abstractmethod
    def replace_rtl(self) -> None:
        """Generate Verilog from build config. Should run on the manager host."""
        raise NotImplementedError

    @abc.abstractmethod
    def build_driver(self) -> None:
        """Build FireSim FPGA driver from build config."""
        raise NotImplementedError

    @abc.abstractmethod
    def build_bitstream(self, bypass: bool = False) -> None:
        """Run bitstream build and terminate the build host at the end.
        Must run after `replace_rtl` and `build_driver` are run.

        Args:
            bypass: If true, immediately return and terminate build host. Used for testing purposes.
        """
        raise NotImplementedError
