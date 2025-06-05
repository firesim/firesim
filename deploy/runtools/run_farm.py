from __future__ import annotations

import re
import logging
import time
import abc
import os
import json
import http.server
import threading
import subprocess
import tempfile
import socketserver
from datetime import timedelta
from fabric.api import run, env, prefix, put, cd, warn_only, local, settings, hide, sudo  # type: ignore
from fabric.contrib.project import rsync_project  # type: ignore
from os.path import join as pjoin 
import pprint
from collections import defaultdict

from awstools.awstools import (
    instances_sorted_by_avail_ip,
    get_run_instances_by_tag_type,
    get_private_ips_for_instances,
    launch_run_instances,
    wait_on_instance_launches,
    terminate_instances,
    get_instance_ids_for_instances,
    aws_resource_names,
    MockBoto3Instance,
)
from runtools.firesim_topology_elements import FireSimPipeNode
from util.inheritors import inheritors
from util.io import firesim_input
from runtools.run_farm_deploy_managers import (
    InstanceDeployManager,
    EC2InstanceDeployManager,
)

from typing import Any, Dict, Optional, List, Union, Set, Type, Tuple, TYPE_CHECKING
from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource

if TYPE_CHECKING:
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode

rootLogger = logging.getLogger()


class Inst(metaclass=abc.ABCMeta):
    """Run farm hosts that can hold simulations or switches.

    Attributes:
        run_farm: handle to run farm this instance is a part of
        MAX_SWITCH_AND_PIPE_SLOTS_ALLOWED: max switch slots allowed (hardcoded)
        switch_slots: switch node slots
        _next_switch_port: next switch port to assign
        MAX_SIM_SLOTS_ALLOWED: max simulations allowed. given by `config_runfarm.yaml`
        sim_slots: simulation node slots
        sim_dir: name of simulation directory on the run host
        instance_deploy_manager: platform specific implementation
        host: hostname or ip address of the instance
        metasimulation_enabled: true if this instance will be running metasimulations
    """

    run_farm: RunFarm

    # switch variables
    # restricted by default security group network model port alloc (10000 to 11000)
    MAX_SWITCH_AND_PIPE_SLOTS_ALLOWED: int = 1000
    switch_slots: List[FireSimSwitchNode]
    _next_switch_port: int

    # pipe variables
    pipe_slots: List[FireSimPipeNode]

    # simulation variables (e.g. maximum supported number of {fpga,meta}-sims)
    MAX_SIM_SLOTS_ALLOWED: int
    sim_slots: List[FireSimServerNode]

    sim_dir: Optional[str]

    # location of fpga db file specifying fpga's available
    fpga_db: Optional[str]

    # instances parameterized by this
    instance_deploy_manager: InstanceDeployManager

    host: Optional[str]

    metasimulation_enabled: bool

    def __init__(
        self,
        run_farm: RunFarm,
        max_sim_slots_allowed: int,
        instance_deploy_manager: Type[InstanceDeployManager],
        sim_dir: Optional[str] = None,
        fpga_db: Optional[str] = None,
        metasimulation_enabled: bool = False,
    ) -> None:
        super().__init__()

        self.run_farm = run_farm

        self.switch_slots = []
        self._next_switch_port = (
            10000  # track ports to allocate for server switch model ports
        )

        self.pipe_slots = []

        self.MAX_SIM_SLOTS_ALLOWED = max_sim_slots_allowed
        self.sim_slots = []

        self.sim_dir = sim_dir
        self.fpga_db = fpga_db
        self.metasimulation_enabled = metasimulation_enabled

        self.instance_deploy_manager = instance_deploy_manager(self)

        self.host = None

    def switch_and_pipe_slots(self) -> int:
        return len(self.switch_slots) + len(self.pipe_slots)

    def set_sim_dir(self, drctry: str) -> None:
        self.sim_dir = drctry

    def get_sim_dir(self) -> str:
        assert self.sim_dir is not None
        return self.sim_dir

    def set_fpga_db(self, f: str) -> None:
        self.fpga_db = f

    def get_fpga_db(self) -> str:
        assert self.fpga_db is not None
        return self.fpga_db

    def get_host(self) -> str:
        assert self.host is not None
        return self.host

    def set_host(self, host: str) -> None:
        self.host = host

    def add_switch(self, firesimswitchnode: FireSimSwitchNode) -> None:
        """Add a switch to the next available switch slot."""
        assert self.switch_and_pipe_slots() < self.MAX_SWITCH_AND_PIPE_SLOTS_ALLOWED
        self.switch_slots.append(firesimswitchnode)
        firesimswitchnode.assign_host_instance(self)

    def add_pipe(self, firesimpipenode: FireSimPipeNode) -> None:
        """Add a pipe to the next available pipe slot."""
        assert self.switch_and_pipe_slots() < self.MAX_SWITCH_AND_PIPE_SLOTS_ALLOWED
        self.pipe_slots.append(firesimpipenode)
        firesimpipenode.assign_host_instance(self)

    def allocate_host_port(self) -> int:
        """Allocate a port to use for something on the host. Successive calls
        will return a new port."""
        retport = self._next_switch_port
        assert (
            retport < 11000
        ), "Exceeded number of ports used on host. You will need to modify your security groups to increase this value."
        self._next_switch_port += 1
        return retport

    def add_simulation(self, firesimservernode: FireSimServerNode) -> None:
        """Add a simulation to the next available slot."""
        assert len(self.sim_slots) < self.MAX_SIM_SLOTS_ALLOWED
        self.sim_slots.append(firesimservernode)
        firesimservernode.assign_host_instance(self)

    def qcow2_support_required(self) -> bool:
        """Return True iff any simulation on this Inst requires qcow2."""
        return any([x.qcow2_support_required() for x in self.sim_slots])

    def terminate_self(self) -> None:
        """Terminate the current host for the Inst."""
        self.run_farm.terminate_by_inst(self)


class RunFarm(metaclass=abc.ABCMeta):
    """Abstract class to represent how to manage run farm hosts (similar to `BuildFarm`).
    In addition to having to implement how to spawn/terminate nodes, the child classes must
    implement helper functions to help topologies map run farm hosts (`Inst`s) to `FireSimNodes`.

    Attributes:
        args: Set of options from the 'args' section of the YAML associated with the run farm.
        default_simulation_dir: default location of the simulation dir on the run farm host
        SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS: dict of host handles to number of FPGAs available
        SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS: dict of host handles to number of metasim slots available
        SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK: dict of host handles to whether an instance is allowed to be used to hold only a switch simulation and nothing else

        SORTED_SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS: sorted 'SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS' by FPGAs available
        SORTED_SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS: sorted 'SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS' by metasim slots available

        run_farm_hosts_dict: list of instances requested (Inst object and one of [None, boto3 object, mock boto3 object, other cloud-specific obj]). TODO: improve this later
        mapper_consumed: dict of allocated instance names to number of allocations of that instance name.
            this mapping API tracks instances allocated not sim slots (it is possible to allocate an instance
            that has some sim slots unassigned)
        metasimulation_enabled: true if this run farm will be running metasimulations

    """

    SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS: Dict[str, int]
    SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS: Dict[str, int]
    SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK: Dict[str, bool]

    SORTED_SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS: List[Tuple[int, str]]
    SORTED_SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS: List[Tuple[int, str]]

    run_farm_hosts_dict: Dict[
        str, List[Tuple[Inst, Optional[Union[EC2InstanceResource, MockBoto3Instance]]]]
    ]
    mapper_consumed: Dict[str, int]

    default_simulation_dir: str
    metasimulation_enabled: bool

    def __init__(self, args: Dict[str, Any], metasimulation_enabled: bool) -> None:
        self.args = args
        self.metasimulation_enabled = metasimulation_enabled
        self.default_simulation_dir = self.args.get(
            "default_simulation_dir", f"/home/{os.environ['USER']}"
        )
        self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS = dict()
        self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS = dict()
        self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK = dict()

    def init_postprocess(self) -> None:
        self.SORTED_SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS = invert_filter_sort(
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS
        )
        self.SORTED_SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS = invert_filter_sort(
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS
        )

    def get_smallest_sim_host_handle(self, num_sims: int) -> str:
        """Return the smallest run host handle (unique string to identify a run host type) that
        supports greater than or equal to num_sims simulations AND has available run hosts
        of that type (according to run host counts you've specified in config_run_farm.ini).
        """
        sorted_slots = None
        if self.metasimulation_enabled:
            sorted_slots = self.SORTED_SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS
        else:
            sorted_slots = self.SORTED_SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS

        for max_simcount, sim_host_handle in sorted_slots:
            if max_simcount < num_sims:
                # instance doesn't support enough sims
                continue
            num_consumed = self.mapper_consumed[sim_host_handle]
            num_allocated = len(self.run_farm_hosts_dict[sim_host_handle])
            if num_consumed >= num_allocated:
                # instance supports enough sims but none are available
                continue
            return sim_host_handle

        rootLogger.critical(
            f"ERROR: No hosts are available to satisfy the request for a host with support for {num_sims} simulation slots. Add more hosts in your run farm configuration (e.g., config_runtime.yaml)."
        )
        raise Exception

    def allocate_sim_host(self, sim_host_handle: str) -> Inst:
        """Let user allocate and use an run host (assign sims, etc.) given it's handle."""
        rootLogger.info(f"run_farm_hosts_dict {self.run_farm_hosts_dict}")
        inst_tup = self.run_farm_hosts_dict[sim_host_handle][
            self.mapper_consumed[sim_host_handle]
        ]
        inst_ret = inst_tup[0]
        self.mapper_consumed[sim_host_handle] += 1
        return inst_ret

    def get_switch_only_host_handle(self) -> str:
        """Get the default run host handle (unique string to identify a run host type) that can
        host switch simulations.
        """
        for sim_host_handle, switch_ok in sorted(
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK.items(), key=lambda x: x[0]
        ):
            if not switch_ok:
                # cannot use this handle for switch-only mapping
                continue

            num_consumed = self.mapper_consumed[sim_host_handle]
            num_allocated = len(self.run_farm_hosts_dict[sim_host_handle])
            if num_consumed >= num_allocated:
                # instance supports enough sims but none are available
                continue
            return sim_host_handle

        rootLogger.critical(
            f"ERROR: No hosts are available to satisfy the request for a host with support for running only switches. Add more hosts in your run farm configuration (e.g., config_runtime.yaml)."
        )
        raise Exception

    @abc.abstractmethod
    def post_launch_binding(self, mock: bool = False) -> None:
        """Bind launched platform API objects to run hosts (only used in firesim-managed runfarms).

        Args:
            mock: In AWS case, for testing, assign mock boto objects.
        """
        raise NotImplementedError

    @abc.abstractmethod
    def launch_run_farm(self) -> None:
        """Launch run hosts for simulations."""
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_run_farm(
        self, terminate_some_dict: Dict[str, int], forceterminate: bool
    ) -> None:
        """Terminate run hosts for simulations.

        Args:
            terminate_some_dict: Dict of run host handles to amount of that type to terminate.
            forceterminate: Don't prompt user to terminate.
        """
        raise NotImplementedError

    @abc.abstractmethod
    def get_all_host_nodes(self) -> List[Inst]:
        """Return all run host nodes."""
        raise NotImplementedError

    @abc.abstractmethod
    def get_all_bound_host_nodes(self) -> List[Inst]:
        """Return all run host nodes that are ready to use (bound to relevant objects)."""
        raise NotImplementedError

    @abc.abstractmethod
    def lookup_by_host(self, host: str) -> Inst:
        """Return run farm host based on host."""
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_by_inst(self, inst: Inst) -> None:
        """Terminate run farm host based on Inst object."""
        raise NotImplementedError


def invert_filter_sort(input_dict: Dict[str, int]) -> List[Tuple[int, str]]:
    """Take a dict, convert to list of pairs, flip key and value,
    remove all keys equal to zero, then sort on the new key."""
    out_list = [(y, x) for x, y in list(input_dict.items())]
    out_list = list(filter(lambda x: x[0] != 0, out_list))
    return sorted(out_list, key=lambda x: x[0])


class AWSEC2F1(RunFarm):
    """This manages the set of AWS resources requested for the run farm.

    Attributes:
        run_farm_tag: tag given to instances launched in this run farm
        always_expand_run_farm: enable expanding run farm on each "launchrunfarm" call
        launch_timeout:
        run_instance_market: host market to use
        spot_interruption_behavior: if using spot instances, determine the interrupt behavior
        spot_max_price: if using spot instances, determine the max price
    """

    run_farm_tag: str
    always_expand_run_farm: bool
    launch_timeout: timedelta
    run_instance_market: str
    spot_interruption_behavior: str
    spot_max_price: str

    def __init__(self, args: Dict[str, Any], metasimulation_enabled: bool) -> None:
        super().__init__(args, metasimulation_enabled)

        self._parse_args()

        self.init_postprocess()

    def _parse_args(self) -> None:
        run_farm_tag_prefix = (
            ""
            if "FIRESIM_RUNFARM_PREFIX" not in os.environ
            else os.environ["FIRESIM_RUNFARM_PREFIX"]
        )
        if run_farm_tag_prefix != "":
            run_farm_tag_prefix += "-"

        self.run_farm_tag = run_farm_tag_prefix + self.args["run_farm_tag"]

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict["runfarmprefix"] is not None:
            # if specified, further prefix runfarmtag
            self.run_farm_tag = (
                aws_resource_names_dict["runfarmprefix"] + "-" + self.run_farm_tag
            )

        self.always_expand_run_farm = self.args["always_expand_run_farm"]

        if "launch_instances_timeout_minutes" in self.args:
            self.launch_timeout = timedelta(
                minutes=int(self.args["launch_instances_timeout_minutes"])
            )
        else:
            self.launch_timeout = (
                timedelta()
            )  # default to legacy behavior of not waiting

        self.run_instance_market = self.args["run_instance_market"]
        self.spot_interruption_behavior = self.args["spot_interruption_behavior"]
        self.spot_max_price = self.args["spot_max_price"]

        dispatch_dict = dict(
            [(x.__name__, x) for x in inheritors(InstanceDeployManager)]
        )

        default_platform = "EC2InstanceDeployManager"

        runhost_specs = dict()
        for specinfo in self.args["run_farm_host_specs"]:
            specinfo_value = next(iter(specinfo.items()))
            runhost_specs[specinfo_value[0]] = specinfo_value[1]

        # populate mapping helpers based on runhost_specs:
        for runhost_spec_name, runhost_spec in runhost_specs.items():
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[runhost_spec_name] = runhost_spec[
                "num_fpgas"
            ]
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[runhost_spec_name] = runhost_spec[
                "num_metasims"
            ]
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[runhost_spec_name] = runhost_spec[
                "use_for_switch_only"
            ]

        runhosts_list = self.args["run_farm_hosts_to_use"]

        self.run_farm_hosts_dict = defaultdict(list)
        self.mapper_consumed = defaultdict(int)

        for runhost in runhosts_list:
            if not isinstance(runhost, dict):
                raise Exception(f"Invalid runhost count definition for {runhost}.")

            items = runhost.items()
            assert (
                len(items) == 1
            ), f"dict type 'runhost' items map a single EC2 instance name to a number. Not {pprint.pformat(runhost)}"

            inst_handle, num_insts = next(iter(items))

            if inst_handle not in runhost_specs.keys():
                raise Exception(f"Unknown runhost handle of {runhost}")

            num_sim_slots = 0
            if self.metasimulation_enabled:
                num_sim_slots = self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[inst_handle]
            else:
                num_sim_slots = self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[inst_handle]

            host_spec = runhost_specs[inst_handle]
            platform = host_spec.get("override_platform", default_platform)
            simulation_dir = host_spec.get(
                "override_simulation_dir", self.default_simulation_dir
            )

            insts: List[
                Tuple[Inst, Optional[Union[EC2InstanceResource, MockBoto3Instance]]]
            ] = []
            for _ in range(num_insts):
                insts.append(
                    (
                        Inst(
                            self,
                            num_sim_slots,
                            dispatch_dict[platform],
                            simulation_dir,
                            None,
                            self.metasimulation_enabled,
                        ),
                        None,
                    )
                )
            self.run_farm_hosts_dict[inst_handle] = insts
            self.mapper_consumed[inst_handle] = 0

    def bind_mock_instances_to_objects(self) -> None:
        """Only used for testing. Bind mock Boto3 instances to objects."""
        for inst_handle, inst_list in sorted(
            self.run_farm_hosts_dict.items(), key=lambda x: x[0]
        ):
            for idx, run_farm_host_tup in enumerate(inst_list):
                boto_obj = MockBoto3Instance()
                inst = run_farm_host_tup[0]
                inst.set_host(boto_obj.private_ip_address)
                inst_list[idx] = (inst, boto_obj)
            self.run_farm_hosts_dict[inst_handle] = inst_list

    def bind_real_instances_to_objects(self) -> None:
        """Attach running instances to the Run Farm."""
        # fetch instances based on tag,
        # populate IP addr list for use in the rest of our tasks.
        # we always sort by private IP when handling instances
        available_instances_per_handle = {}
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            available_instances_per_handle[sim_host_handle] = (
                instances_sorted_by_avail_ip(
                    get_run_instances_by_tag_type(self.run_farm_tag, sim_host_handle)
                )
            )

        message = """Insufficient {}. Did you run `firesim launchrunfarm`?"""
        # confirm that we have the correct number of instances
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            if not (
                len(available_instances_per_handle[sim_host_handle])
                >= len(self.run_farm_hosts_dict[sim_host_handle])
            ):
                rootLogger.warning(message.format(sim_host_handle))

        ipmessage = """Using {} instances with IPs:\n{}"""
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            rootLogger.debug(
                ipmessage.format(
                    sim_host_handle,
                    str(
                        get_private_ips_for_instances(
                            available_instances_per_handle[sim_host_handle]
                        )
                    ),
                )
            )

        # assign boto3 instance objects to our instance objects
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            for index, instance in enumerate(
                available_instances_per_handle[sim_host_handle]
            ):
                inst_tup = self.run_farm_hosts_dict[sim_host_handle][index]
                inst = inst_tup[0]
                inst.set_host(instance.private_ip_address)
                new_tup = (inst, instance)
                self.run_farm_hosts_dict[sim_host_handle][index] = new_tup

    def post_launch_binding(self, mock: bool = False) -> None:
        if mock:
            self.bind_mock_instances_to_objects()
        else:
            self.bind_real_instances_to_objects()

    def launch_run_farm(self) -> None:
        runfarmtag = self.run_farm_tag
        runinstancemarket = self.run_instance_market
        spotinterruptionbehavior = self.spot_interruption_behavior
        spotmaxprice = self.spot_max_price
        timeout = self.launch_timeout
        always_expand = self.always_expand_run_farm

        # actually launch the instances
        launched_instance_objs = {}
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            expected_number_of_instances_of_handle = len(
                self.run_farm_hosts_dict[sim_host_handle]
            )
            launched_instance_objs[sim_host_handle] = launch_run_instances(
                sim_host_handle,
                expected_number_of_instances_of_handle,
                runfarmtag,
                runinstancemarket,
                spotinterruptionbehavior,
                spotmaxprice,
                timeout,
                always_expand,
            )

        # wait for instances to get to running state, so that they have been
        # assigned IP addresses
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            wait_on_instance_launches(
                launched_instance_objs[sim_host_handle], sim_host_handle
            )

    def terminate_run_farm(
        self, terminate_some_dict: Dict[str, int], forceterminate: bool
    ) -> None:
        runfarmtag = self.run_farm_tag

        # make sure requested instance handles are valid
        terminate_some_requested_handles_set = set(terminate_some_dict.keys())
        allowed_handles_set = set(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS)
        not_allowed_handles = terminate_some_requested_handles_set - allowed_handles_set
        if len(not_allowed_handles) != 0:
            # the terminatesome logic becomes messy if you have invalid instance
            # handles specified, so just exit and indicate error
            rootLogger.critical(
                "WARNING: You have requested --terminatesome for the following invalid instance handles. Nothing has been terminated.\n"
                + str(not_allowed_handles)
            )
            exit(1)

        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        all_instances = dict()
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            all_instances[sim_host_handle] = instances_sorted_by_avail_ip(
                get_run_instances_by_tag_type(runfarmtag, sim_host_handle)
            )

        all_instance_ids = dict()
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            all_instance_ids[sim_host_handle] = get_instance_ids_for_instances(
                all_instances[sim_host_handle]
            )

        if len(terminate_some_dict) != 0:
            # In this mode, only terminate instances that are specifically supplied.
            for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
                if (
                    sim_host_handle in terminate_some_dict
                    and terminate_some_dict[sim_host_handle] > 0
                ):
                    termcount = terminate_some_dict[sim_host_handle]
                    # grab the last N instances to terminate
                    all_instance_ids[sim_host_handle] = all_instance_ids[
                        sim_host_handle
                    ][-termcount:]
                else:
                    all_instance_ids[sim_host_handle] = []

        rootLogger.critical("IMPORTANT!: This will terminate the following instances:")
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            rootLogger.critical(sim_host_handle)
            rootLogger.critical(all_instance_ids[sim_host_handle])

        if not forceterminate:
            # --forceterminate was not supplied, so confirm with the user
            userconfirm = firesim_input(
                "Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n"
            )
        else:
            userconfirm = "yes"

        if userconfirm == "yes":
            for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
                if len(all_instance_ids[sim_host_handle]) != 0:
                    terminate_instances(all_instance_ids[sim_host_handle], False)
            rootLogger.critical(
                "Instances terminated. Please confirm in your AWS Management Console."
            )
        else:
            rootLogger.critical("Termination cancelled.")

    def get_all_host_nodes(self) -> List[Inst]:
        all_insts = []
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            inst_list = self.run_farm_hosts_dict[sim_host_handle]
            for inst, boto in inst_list:
                all_insts.append(inst)
        return all_insts

    def get_all_bound_host_nodes(self) -> List[Inst]:
        all_insts = []
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            inst_list = self.run_farm_hosts_dict[sim_host_handle]
            for inst, boto in inst_list:
                if boto is not None:
                    all_insts.append(inst)
        return all_insts

    def lookup_by_host(self, host) -> Inst:
        for host_node in self.get_all_bound_host_nodes():
            if host_node.get_host() == host:
                return host_node
        assert False, f"Unable to find host node by {host}"

    def terminate_by_inst(self, inst: Inst) -> None:
        """Terminate run farm host based on host."""
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            inst_list = self.run_farm_hosts_dict[sim_host_handle]
            for inner_inst, boto in inst_list:
                if inner_inst.get_host() == inst.get_host():
                    # EC2InstanceResource can only be used for typing checks
                    # preventing its use for the isinstance() check
                    assert boto is not None and not isinstance(boto, MockBoto3Instance)
                    instanceids = get_instance_ids_for_instances([boto])
                    terminate_instances(instanceids, dryrun=False)


class ExternallyProvisioned(RunFarm):
    """This manages the set of externally provisioned instances. This class doesn't manage
    launch/terminating instances. It is assumed that the instances are "ready to use".

    Attributes:
    """

    def __init__(self, args: Dict[str, Any], metasimulation_enabled: bool) -> None:
        super().__init__(args, metasimulation_enabled)

        self._parse_args()

        self.init_postprocess()

    def _parse_args(self) -> None:
        dispatch_dict = dict(
            [(x.__name__, x) for x in inheritors(InstanceDeployManager)]
        )

        default_platform = self.args.get("default_platform")
        default_fpga_db = self.args.get("default_fpga_db")

        runhost_specs = dict()
        for specinfo in self.args["run_farm_host_specs"]:
            specinfo_value = next(iter(specinfo.items()))
            runhost_specs[specinfo_value[0]] = specinfo_value[1]

        runhosts_list = self.args["run_farm_hosts_to_use"]

        self.run_farm_hosts_dict = defaultdict(list)
        self.mapper_consumed = defaultdict(int)

        for runhost in runhosts_list:
            if not isinstance(runhost, dict):
                raise Exception(f"Invalid runhost to spec mapping for {runhost}.")

            items = runhost.items()

            assert (
                len(items) == 1
            ), f"dict type 'run_hosts' items map a single host name to a host spec. Not: {pprint.pformat(runhost)}"

            ip_addr, host_spec_name = next(iter(items))

            if host_spec_name not in runhost_specs.keys():
                raise Exception(f"Unknown runhost spec of {host_spec_name}")

            host_spec = runhost_specs[host_spec_name]

            # populate mapping helpers based on runhost_specs:
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[ip_addr] = host_spec["num_fpgas"]
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[ip_addr] = host_spec[
                "num_metasims"
            ]
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[ip_addr] = host_spec[
                "use_for_switch_only"
            ]

            num_sims = 0
            if self.metasimulation_enabled:
                num_sims = host_spec.get("num_metasims")
            else:
                num_sims = host_spec.get("num_fpgas")
            platform = host_spec.get("override_platform", default_platform)
            simulation_dir = host_spec.get(
                "override_simulation_dir", self.default_simulation_dir
            )
            fpga_db = host_spec.get("override_fpga_db", default_fpga_db)

            inst = Inst(
                self,
                num_sims,
                dispatch_dict[platform],
                simulation_dir,
                fpga_db,
                self.metasimulation_enabled,
            )
            inst.set_host(ip_addr)
            assert (
                not ip_addr in self.run_farm_hosts_dict
            ), f"Duplicate host name found in 'run_farm_hosts': {ip_addr}"
            self.run_farm_hosts_dict[ip_addr] = [(inst, None)]
            self.mapper_consumed[ip_addr] = 0

    def post_launch_binding(self, mock: bool = False) -> None:
        return

    def launch_run_farm(self) -> None:
        rootLogger.info(
            f"WARNING: Skipping launchrunfarm since run hosts are externally provisioned."
        )
        return

    def terminate_run_farm(
        self, terminate_some_dict: Dict[str, int], forceterminate: bool
    ) -> None:
        rootLogger.info(
            f"WARNING: Skipping terminaterunfarm since run hosts are externally provisioned."
        )
        return

    def get_all_host_nodes(self) -> List[Inst]:
        all_insts = []
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            inst_list = self.run_farm_hosts_dict[sim_host_handle]
            for inst, cloudobj in inst_list:
                all_insts.append(inst)
        return all_insts

    def get_all_bound_host_nodes(self) -> List[Inst]:
        return self.get_all_host_nodes()

    def lookup_by_host(self, host: str) -> Inst:
        for host_node in self.get_all_bound_host_nodes():
            if host_node.get_host() == host:
                return host_node
        assert False, f"Unable to find host node by {host} host name"

    def terminate_by_inst(self, inst: Inst) -> None:
        rootLogger.info(
            f"WARNING: Skipping terminate_by_inst since run hosts are externally provisioned."
        )
        return

class LocalProvisionedVM(RunFarm): # run_farm_type
    """Manages the set of locally provisioned VMs with FPGAs passed through to them. This class is responsible for spinning up the VMs and shutting down those VMs after the simulation is done.

    Attributes:
        vm_ip_to_instance: dict of IP address to instance name
    """

    run_farm_hosts_dict: Dict[
        str, List[Tuple[Inst, Optional[Union[EC2InstanceResource, MockBoto3Instance, str]]]]
    ]
    vm_name: str = f"jammy_cis-{local('whoami', capture=True)}"
    vm_username: str = local("whoami", capture=True)
    
    iso_location: str = "/home/chief/Downloads/ubuntu-22.04.5-live-server-amd64.iso" # TODO: update location dynamically

    def __init__(self, args: Dict[str, Any], metasimulation_enabled: bool) -> None :
        super().__init__(args, metasimulation_enabled) # if metasim enabled, we give it to super to handle

        self._parse_args()  # parse args in yaml file

        self.init_postprocess()

    def _parse_args(self) -> None: # parse args in yaml file
        dispatch_dict = dict(
            [(x.__name__, x) for x in inheritors(InstanceDeployManager)]
        ) # calls specific instance deploy manager that is correct for the platform (vcu118, u250)

        default_platform = self.args.get("default_platform")
        default_fpga_db = self.args.get("default_fpga_db")

        runhost_specs = dict()
        for specinfo in self.args["run_farm_host_specs"]: # to write a new run farm host spec, add it in externally_provisioned.yaml
            specinfo_value = next(iter(specinfo.items()))
            runhost_specs[specinfo_value[0]] = specinfo_value[1]

        runhosts_list = self.args["run_farm_hosts_to_use"] # this is in your config_runtime.yaml

        self.run_farm_hosts_dict = defaultdict(list) 
        self.mapper_consumed = defaultdict(int)
        
        # read firesim_vm_status.json to see if the VM has been setup already - if yes, remap the ip
        firesim_vm_status_path = pjoin(
            os.path.dirname(os.path.abspath(__file__)), "..", "firesim_vm_status.json"
        )
        
        with open(firesim_vm_status_path, "r") as db_file:
            try:
                db_data = json.load(db_file)
            except json.JSONDecodeError as e:
                raise ValueError(f"Error decoding JSON from firesim_vm_status.json: {e}")

        for runhost in runhosts_list:
            if not isinstance(runhost, dict):
                raise Exception(f"Invalid runhost to spec mapping for {runhost}.")

            items = runhost.items()

            assert (
                len(items) == 1
            ), f"dict type 'run_hosts' items map a single host name to a host spec. Not: {pprint.pformat(runhost)}"

            ip_addr, host_spec_name = next(iter(items))

            if host_spec_name not in runhost_specs.keys():
                raise Exception(f"Unknown runhost spec of {host_spec_name}")

            host_spec = runhost_specs[host_spec_name]

            # populate object based on runhost_specs:
            if db_data["vm_setup"]: # use the IP from the db if vm has been setup already
                self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[db_data["vm_ip"]] = host_spec["num_fpgas"]
                self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[db_data["vm_ip"]] = host_spec[
                    "num_metasims"
                ]
                self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[db_data["vm_ip"]] = host_spec[
                    "use_for_switch_only"
                ]
            else: 
                self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[ip_addr] = host_spec["num_fpgas"]
                self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[ip_addr] = host_spec[
                    "num_metasims"
                ]
                self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[ip_addr] = host_spec[
                    "use_for_switch_only"
                ]

            num_sims = 0
            if self.metasimulation_enabled:
                num_sims = host_spec.get("num_metasims")
            else:
                num_sims = host_spec.get("num_fpgas")
            platform = host_spec.get("override_platform", default_platform)
            simulation_dir = host_spec.get(
                "override_simulation_dir", self.default_simulation_dir
            )
            fpga_db = host_spec.get("override_fpga_db", default_fpga_db)

            inst = Inst(
                self,
                num_sims,
                dispatch_dict[platform],
                simulation_dir,
                fpga_db,
                self.metasimulation_enabled,
            )
            
            if db_data["vm_setup"]: # use the IP from the db if vm has been setup already
                inst.set_host(db_data["vm_ip"])
                assert (
                    not db_data["vm_ip"] in self.run_farm_hosts_dict
                ), f"Duplicate host name found in 'run_farm_hosts': {db_data['vm_ip']}"
                self.run_farm_hosts_dict[db_data["vm_ip"]] = [(inst, self.vm_name)]
                self.mapper_consumed[db_data["vm_ip"]] = 0
            else:
                inst.set_host(ip_addr)
                assert (
                    not ip_addr in self.run_farm_hosts_dict
                ), f"Duplicate host name found in 'run_farm_hosts': {ip_addr}"
                self.run_farm_hosts_dict[ip_addr] = [(inst, self.vm_name)]
                self.mapper_consumed[ip_addr] = 0
            
            

    def post_launch_binding(self, mock: bool = False) -> None:
        # binding of IP with instance name completed in launch_run_farm since there are no objects, its just a str [ip] : str [vm name] binding
        return

    def launch_run_farm(self) -> None:
        # schema:
        #     [
        #         "fpga_bdf": {
        #             busy: bool, # whether the FPGA is being used by a vm or not
        #             vm_name: str, # name of the VM that is using the FPGA
        #             vm_ip: str, # IP address of the VM that is using the FPGA
        #             in_use_by: str, # name of the job/user that is using the FPGA
        #         }
        #     ]
        # look at the database on the host machine, find list of FPGAs free, if num free > num requested by the spec, grab their bdfs and attach them to the VM, record vm IP in the database

        # open /opt/firesim-vm-host.db.json, count number of fpgas not busy

        host_machine_ip = list(self.args["run_farm_hosts_to_use"][0].keys())[0]  # assuming the first host is the one we are using - typcially for local setup you only have 1 host with `n` fpgas
        
        # acquire lock of database on host machine
        db_path = "/opt/firesim-vm-host-db.json"
        lock_path = "/opt/firesim-vm-host-db.lock"
        
        # Step 1: Read and lock file remotely
        with tempfile.NamedTemporaryFile(mode='w+', delete=False) as local_db:
            local_tmp_path = local_db.name
            
        rootLogger.info(f"Reading database file {db_path} from host {host_machine_ip} to local temp file {local_tmp_path}")
        
        # Acquire lock, copy file
        try:
            subprocess.run([
                "ssh", host_machine_ip,
                f"flock {lock_path} cat {db_path}"
            ], check=True, stdout=open(local_tmp_path, "wb"))
        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"Failed to read database file {db_path} on host {host_machine_ip}: {e} -- Did you run sudo ./setup-firesim-vm-host?")
    
        # Step 2: Load + modify JSON locally
        with open(local_tmp_path, "r") as db_file:
            try:
                host_machine_fpgas_db = json.load(db_file)
            except json.JSONDecodeError as e:
                raise ValueError(f"Error decoding JSON from {db_path}: {e}")
        rootLogger.info(f"Loaded database from {db_path}, locally.")

        # get number of FPGAs that are not busy
        free_fpgas = [
            fpga for fpga in host_machine_fpgas_db if not host_machine_fpgas_db[fpga]["busy"]
        ]
        rootLogger.info(f"Number of free FPGAs: {len(free_fpgas)}")

        # get number of FPGAs requested by the spec
        num_requested_fpgas = self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[host_machine_ip]

        rootLogger.info(f"Number of FPGAs requested by the spec: {num_requested_fpgas}")

        # check if there are enough free FPGAs available
        if len(free_fpgas) < num_requested_fpgas:
            raise RuntimeError(
                f"Not enough free FPGAs available. Requested: {num_requested_fpgas}, Available: {len(free_fpgas)}"
            )

        # grab their bdfs and attach them to the VM, set them as busy, record vm IP in the database
        
        fpgas_to_attach = free_fpgas[:num_requested_fpgas]
        
        # set those fpgas as busy in the database & write to file
        for fpga in fpgas_to_attach:
            host_machine_fpgas_db[fpga]["busy"] = True
            host_machine_fpgas_db[fpga]["vm_name"] = self.vm_name  # use the VM name + username to avoid collisions
            host_machine_fpgas_db[fpga]["in_use_by"] = local("whoami", capture=True)
        
        # Step 3: Write it back to file
        with open(local_tmp_path, "w") as db_file:
            try:
                json.dump(host_machine_fpgas_db, db_file, indent=2)
            except TypeError as e:
                raise ValueError(f"Error writing JSON to {db_path}: {e}")
        rootLogger.info(f"Updated database with attached FPGAs: {json.dumps(host_machine_fpgas_db, indent=2)}")
        
        # Step 4: scp back while holding the lock
        subprocess.run([
            "ssh", host_machine_ip,
            f"flock {lock_path} bash -c 'cat > {db_path}'"
        ], input=open(local_tmp_path, "rb").read(), check=True)

        # prepare xml file for attaching the FPGAs to the VM -- see vm-pci-attach-frame.xml for example
        # clear the existing pci-attach xml file
        pci_attach_xml_path = pjoin(
            os.path.dirname(os.path.abspath(__file__)), "..", "vm-pci-attach.xml"
        )
        if os.path.isfile(pci_attach_xml_path):
            os.remove(pci_attach_xml_path)
        
        # create the XML for each FPGA to attach to the VM & write <devices> section        
        for fpga in fpgas_to_attach:
            bdf_parts = fpga.split(":")
            if len(bdf_parts) != 4:
                raise ValueError(f"Invalid BDF format: {fpga}. Expected format: bus:slot:function")
            _, bus, slot, function = bdf_parts
            # create the XML file for attaching the FPGA to the VM
            # bus number cannot be the same as {bus} otherwise you get an conficting bdf error
            pci_attach_xml = f"""<hostdev mode='subsystem' type='pci' managed='yes'>
                <source>
                    <address domain='0x0000' bus='0x{bus}' slot='0x{slot}' function='0x{function}'/>
                </source>
                <rom bar='off'/>
                <address type='pci' domain='0x0000' bus='0x07' slot='0x{slot}' function='0x{function}'/>
            </hostdev>"""
            # write the XML to a file
            pci_attach_xml_path = pjoin(
                os.path.dirname(os.path.abspath(__file__)), "..", "vm-pci-attach.xml"
            )
            # append the XML to the file
            with open(pci_attach_xml_path, "a") as pci_attach_xml_fd:
                pci_attach_xml_fd.write(pci_attach_xml + "\n")
        
        rootLogger.info(f"Prepared PCIe attach XML for FPGAs: {fpgas_to_attach}")
        
        # there should only be 1 VM spun up no matter how many FPGAs we want - all FPGAs will get attached to the same VM (1 VM / job)

        # create the VM - run vm-create.sh + attach pcie device
        rootLogger.info("running vm-create...")
        env.host_string = host_machine_ip # set the host string to localhost since we are running this on the local machine
        rootLogger.info(f"running as: {env.host_string}")
        run(f"""{pjoin(
            os.path.dirname(os.path.abspath(__file__)), "..", "vm-create"
        )} {self.vm_name} {self.iso_location} {pjoin(os.path.dirname(os.path.abspath(__file__)), '..','vm-pci-attach.xml',)}""") # will auto restart after installation completes
        rootLogger.info(
            "ran vm-create to create the VM + attached pcie devices"
        )

        # spin & wait for the VM to be up (from reboot after installation)
        while True:
            if "running" in run(f"virsh domstate {self.vm_name}"): # this doesn't tell us the system has booted -- only its "on"
                with settings(warn_only=True):
                    ip_addr = run(
                        " ".join(["""for mac in `virsh domiflist""", self.vm_name, """|grep -o -E "([0-9a-f]{2}:){5}([0-9a-f]{2})"` ; do arp -e |grep $mac  |grep -o -P "^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}" ; done
                        """]),
                    )
                    if (ip_addr != "") and ("SSH" in run(f"echo | nc {ip_addr} 22")): # use nc here to ensure that the VM is actually up and running, we arent just looking for ip assigment here
                        break
            time.sleep(1)
        rootLogger.info("VM is up and running")
    
        # grab VM IP - https://stackoverflow.com/questions/19057915/libvirt-fetch-ipv4-address-from-guest
        # TODO: ensure DHCP lease doesn't expire/IP doesn't change
        ip_addr = run(
            " ".join(
                [
                    """for mac in `virsh domiflist""",
                    self.vm_name,
                    """|grep -o -E "([0-9a-f]{2}:){5}([0-9a-f]{2})"` ; do arp -e |grep $mac  |grep -o -P "^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}" ; done
                        """,
                ]
            )
        )

        # Step 1: Read and lock file remotely
        with tempfile.NamedTemporaryFile(mode='w+', delete=False) as local_db:
            local_tmp_path = local_db.name
            
        rootLogger.info(f"Reading database file {db_path} from host {host_machine_ip} to local temp file {local_tmp_path}")
        
        # Acquire lock, copy file
        try:
            subprocess.run([
                "ssh", host_machine_ip,
                f"flock {lock_path} cat {db_path}"
            ], check=True, stdout=open(local_tmp_path, "wb"))
        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"Failed to read database file {db_path} on host {host_machine_ip}: {e} -- Did you run sudo ./setup-firesim-vm-host?")
    
        # Step 2: Load + modify JSON locally
        with open(local_tmp_path, "r") as db_file:
            try:
                host_machine_fpgas_db = json.load(db_file)
            except json.JSONDecodeError as e:
                raise ValueError(f"Error decoding JSON from {db_path}: {e}")
        rootLogger.info(f"Loaded database from {db_path}, locally.")
        
        # set ip address in host machines db
        for fpga in fpgas_to_attach:
            host_machine_fpgas_db[fpga]["vm_ip"] = ip_addr
            
        # Step 3: Write it back to file
        with open(local_tmp_path, "w") as db_file:
            try:
                json.dump(host_machine_fpgas_db, db_file, indent=2)
            except TypeError as e:
                raise ValueError(f"Error writing JSON to {db_path}: {e}")
        rootLogger.info(f"Updated database with attached FPGAs: {json.dumps(host_machine_fpgas_db, indent=2)}")
        
        # Step 4: scp back while holding the lock
        subprocess.run([
            "ssh", host_machine_ip,
            f"flock {lock_path} bash -c 'cat > {db_path}'"
        ], input=open(local_tmp_path, "rb").read(), check=True)

        # write to local firesim_vm_status.json
        firesim_vm_status_path = pjoin(
            os.path.dirname(os.path.abspath(__file__)), "..", "firesim_vm_status.json"
        )
        with open(firesim_vm_status_path, "r+") as f:
            firesim_vm_status = json.load(f)
            firesim_vm_status = {
                "vm_setup": True,
                "vm_ip": ip_addr,
            }
            f.seek(0)
            json.dump(firesim_vm_status, f, indent=2)
            f.truncate()
    
        # install cmake, gcc, etc
        logging.getLogger("paramiko").setLevel(logging.DEBUG)
        rootLogger.info(f"{self.vm_username}@{ip_addr}")
        env.host_string = f"{self.vm_username}@{ip_addr}" # this changes the host_string for subsequent run() calls until program exits
        env.key_filename = pjoin(
            os.path.dirname(os.path.abspath(__file__)), "..", "vm-cloud-init-configs", "firesim_vm_ed25519"
        )  # this is the key we use to ssh into the VM, set in vm-cloud-init-configs/user-data

        # ssh into the vm is key based, set in vm-cloud-init-configs/user-data
        rootLogger.info("Installing build-essential...")
        run("""sudo apt-get install -y build-essential""", shell=True)

        # install scripts to /usr/local/bin
        rootLogger.info("Installing scripts to /usr/local/bin...")
        run("""git clone https://github.com/firesim/firesim ~/firesim""", shell=True)
        run("""cd firesim && sudo cp deploy/sudo-scripts/* /usr/local/bin""", shell=True)
        run("""cd firesim && sudo cp platforms/xilinx_alveo_u250/scripts/* /usr/local/bin""", shell=True) # TODO: this is hardcoded to the u250 platform, need to make it more generic
        run("""rm -rf ~/firesim""", shell=True) # remove the repo after copying the scripts

        # install xdma & xcsec drivers
        rootLogger.info("Installing xdma & xcsec drivers...")

        run("""git clone https://github.com/Xilinx/dma_ip_drivers ~/dma_ip_drivers""", shell=True)

        run("""cd ~/dma_ip_drivers/XDMA/linux-kernel/xdma && sudo make install""", shell=True)

        run("""git clone https://github.com/paulmnt/dma_ip_drivers ~/dma_ip_drivers_xvsec""", shell=True) 

        run("""cd ~/dma_ip_drivers_xvsec/XVSEC/linux-kernel && sudo make clean all && sudo make install""", shell=True)
        
    def terminate_run_farm(
        self, terminate_some_dict: Dict[str, int], forceterminate: bool
    ) -> None:
        
        # run everything here on host machine, not vms
        host_machine_ip = list(self.args["run_farm_hosts_to_use"][0].keys())[0] 
        env.host_string = host_machine_ip 
        db_path = "/opt/firesim-vm-host-db.json"
        lock_path = "/opt/firesim-vm-host-db.lock"
        if (not forceterminate):
            userconfirm = firesim_input(
                "Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n"
            )
        else: 
            userconfirm = "yes"

        if userconfirm == "yes":
            # detach FPGAs - technically not needed -- deleting a vm will automatically detach -- but good practice
            run(
                f"virsh detach-device {self.vm_name} --file {pjoin(os.path.dirname(os.path.abspath(__file__)), '..','vm-pci-attach.xml',)} --persistent"
            )
            rootLogger.info("Detached PCIe device from VM")

            # first send shutdown signal, if that doesn't work, force shutdown
            run(f"virsh shutdown {self.vm_name} --mode acpi")
            rootLogger.info("Shutdown signal sent to VM")   

            time.sleep(10)
            # if still running after 10 seconds, then something went wrong, force shutdown
            if "running" in run(f"virsh domstate {self.vm_name}"):
                rootLogger.info("Force shutting down VM...")
                run(f"virsh destroy {self.vm_name}")
                rootLogger.info("Force shut down VM")

            # remove the VM
            run(f"virsh undefine {self.vm_name} --nvram --remove-all-storage")
            rootLogger.info("Removed VM")

            # empty run_farm_hosts_dict
            self.run_farm_hosts_dict.clear()
            # remove from SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS.clear()
            # remove from SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS.clear()
            # remove from SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK.clear()
            # remove from mapper_consumed
            self.mapper_consumed.clear()
            
            # remove the VM IP from the firesim_vm_status.json
            firesim_vm_status_path = pjoin(
                os.path.dirname(os.path.abspath(__file__)), "..", "firesim_vm_status.json"
            )
            with open(firesim_vm_status_path, "r+") as f:
                firesim_vm_status = json.load(f)
                firesim_vm_status = {
                    "vm_setup": False,
                    "vm_ip": "",
                }
                f.seek(0)
                json.dump(firesim_vm_status, f, indent=2)
                f.truncate()
            
            # set fpga as free in the host machine's database
            # Step 1: Read and lock file remotely
            with tempfile.NamedTemporaryFile(mode='w+', delete=False) as local_db:
                local_tmp_path = local_db.name
                
            rootLogger.info(f"Reading database file {db_path} from host {host_machine_ip} to local temp file {local_tmp_path}")
            
            # Acquire lock, copy file
            try:
                subprocess.run([
                    "ssh", host_machine_ip,
                    f"flock {lock_path} cat {db_path}"
                ], check=True, stdout=open(local_tmp_path, "wb"))
            except subprocess.CalledProcessError as e:
                raise RuntimeError(f"Failed to read database file {db_path} on host {host_machine_ip}: {e} -- Did you run sudo ./setup-firesim-vm-host?")
        
            # Step 2: Load + modify JSON locally
            with open(local_tmp_path, "r") as db_file:
                try:
                    host_machine_fpgas_db = json.load(db_file)
                except json.JSONDecodeError as e:
                    raise ValueError(f"Error decoding JSON from {db_path}: {e}")
            rootLogger.info(f"Loaded database from {db_path}, locally.")

            # any fpga that has vm_name as the current VM name, set busy to false and vm_name to empty string
            for fpga in host_machine_fpgas_db:
                if host_machine_fpgas_db[fpga]["vm_name"] == self.vm_name:
                    host_machine_fpgas_db[fpga]["busy"] = False
                    host_machine_fpgas_db[fpga]["vm_name"] = ""
                    host_machine_fpgas_db[fpga]["in_use_by"] = ""
            
            # Step 3: Write it back to file
            with open(local_tmp_path, "w") as db_file:
                try:
                    json.dump(host_machine_fpgas_db, db_file, indent=2)
                except TypeError as e:
                    raise ValueError(f"Error writing JSON to {db_path}: {e}")
            rootLogger.info(f"Updated database with attached FPGAs: {json.dumps(host_machine_fpgas_db, indent=2)}")
            
            # Step 4: scp back while holding the lock
            subprocess.run([
                "ssh", host_machine_ip,
                f"flock {lock_path} bash -c 'cat > {db_path}'"
            ], input=open(local_tmp_path, "rb").read(), check=True)

    def get_all_host_nodes(self) -> List[Inst]:
        all_insts = []
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            inst_list = self.run_farm_hosts_dict[sim_host_handle]
            for inst, vm_name in inst_list:
                all_insts.append(inst)
        return all_insts

    def get_all_bound_host_nodes(self) -> List[Inst]:
        return self.get_all_host_nodes()

    def lookup_by_host(self, host: str) -> Inst:
        for host_node in self.get_all_bound_host_nodes():
            if host_node.get_host() == host:
                return host_node
        assert False, f"Unable to find host node by {host} host name"

    def terminate_by_inst(self, inst: Inst) -> None:
        # there should only be one instance to terminate since we have 1 vm/user or job
        return self.terminate_run_farm(self, {}, True)
