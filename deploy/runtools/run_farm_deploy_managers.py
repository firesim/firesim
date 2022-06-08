""" Run Farm management. """

from __future__ import annotations

import re
import logging
import abc
from fabric.api import prefix, local, run, env, cd, warn_only, put, settings, hide # type: ignore
from fabric.contrib.project import rsync_project # type: ignore
import time
from os.path import join as pjoin

from util.streamlogger import StreamLogger
from awstools.awstools import terminate_instances, get_instance_ids_for_instances

from typing import List, Dict, Optional, Union, TYPE_CHECKING
if TYPE_CHECKING:
    from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode
    from runtools.run_farm import Inst
    from awstools.awstools import MockBoto3Instance

rootLogger = logging.getLogger()

class InstanceDeployManager(metaclass=abc.ABCMeta):
    """Class used to represent different "run platforms" and how to start/stop and setup simulations.

    Attributes:
        parent_node: Run farm host associated with this platform implementation.
    """
    parent_node: Inst

    def __init__(self, parent_node: Inst) -> None:
        """
        Args:
            parent_node: Run farm host to associate with this platform implementation
        """
        self.parent_node = parent_node

    @abc.abstractmethod
    def infrasetup_instance(self) -> None:
        """Run platform specific implementation of how to setup simulations."""
        raise NotImplementedError

    @abc.abstractmethod
    def start_switches_instance(self) -> None:
        """Boot up all the switches for this platform."""
        raise NotImplementedError

    @abc.abstractmethod
    def start_simulations_instance(self) -> None:
        """Boot up all the sims for this platform."""
        raise NotImplementedError

    @abc.abstractmethod
    def kill_switches_instance(self) -> None:
        """Terminate all the switches for this platform."""
        raise NotImplementedError

    @abc.abstractmethod
    def kill_simulations_instance(self, disconnect_all_nbds: bool = True) -> None:
        """Terminate all the sims for this platform."""
        raise NotImplementedError

    @abc.abstractmethod
    def monitor_jobs_instance(self, completed_jobs: List[str], teardown: bool, terminateoncompletion: bool,
            job_results_dir: str) -> Dict[str, Dict[str, bool]]:
        """Job monitoring for this instance."""
        raise NotImplementedError
