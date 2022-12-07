from __future__ import annotations

import re
import logging
import time
import abc
import os
from datetime import timedelta
from fabric.api import run, env, prefix, put, cd, warn_only, local, settings, hide # type: ignore
from fabric.contrib.project import rsync_project # type: ignore
from os.path import join as pjoin
import pprint
from collections import defaultdict

from awstools.awstools import instances_sorted_by_avail_ip, get_run_instances_by_tag_type, get_private_ips_for_instances, launch_run_instances, wait_on_instance_launches, terminate_instances, get_instance_ids_for_instances, aws_resource_names, MockBoto3Instance
from util.inheritors import inheritors
from util.io import firesim_input
from runtools.run_farm_deploy_managers import InstanceDeployManager, EC2InstanceDeployManager

from typing import Any, Dict, Optional, List, Union, Set, Type, Tuple, TYPE_CHECKING
from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
if TYPE_CHECKING:
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode

rootLogger = logging.getLogger()

class Inst(metaclass=abc.ABCMeta):
    """Run farm hosts that can hold simulations or switches.

    Attributes:
        run_farm: handle to run farm this instance is a part of
        MAX_SWITCH_SLOTS_ALLOWED: max switch slots allowed (hardcoded)
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
    MAX_SWITCH_SLOTS_ALLOWED: int = 1000
    switch_slots: List[FireSimSwitchNode]
    _next_switch_port: int

    # simulation variables (e.g. maximum supported number of {fpga,meta}-sims)
    MAX_SIM_SLOTS_ALLOWED: int
    sim_slots: List[FireSimServerNode]

    sim_dir: Optional[str]

    # instances parameterized by this
    instance_deploy_manager: InstanceDeployManager

    host: Optional[str]

    metasimulation_enabled: bool

    def __init__(self, run_farm: RunFarm, max_sim_slots_allowed: int, instance_deploy_manager: Type[InstanceDeployManager], sim_dir: Optional[str] = None, metasimulation_enabled: bool = False) -> None:
        super().__init__()

        self.run_farm = run_farm

        self.switch_slots = []
        self._next_switch_port = 10000 # track ports to allocate for server switch model ports

        self.MAX_SIM_SLOTS_ALLOWED = max_sim_slots_allowed
        self.sim_slots = []

        self.sim_dir = sim_dir
        self.metasimulation_enabled = metasimulation_enabled

        self.instance_deploy_manager = instance_deploy_manager(self)

        self.host = None

    def set_sim_dir(self, drctry: str) -> None:
        self.sim_dir = drctry

    def get_sim_dir(self) -> str:
        assert self.sim_dir is not None
        return self.sim_dir

    def get_host(self) -> str:
        assert self.host is not None
        return self.host

    def set_host(self, host: str) -> None:
        self.host = host

    def add_switch(self, firesimswitchnode: FireSimSwitchNode) -> None:
        """ Add a switch to the next available switch slot. """
        assert len(self.switch_slots) < self.MAX_SWITCH_SLOTS_ALLOWED
        self.switch_slots.append(firesimswitchnode)
        firesimswitchnode.assign_host_instance(self)

    def allocate_host_port(self) -> int:
        """ Allocate a port to use for something on the host. Successive calls
        will return a new port. """
        retport = self._next_switch_port
        assert retport < 11000, "Exceeded number of ports used on host. You will need to modify your security groups to increase this value."
        self._next_switch_port += 1
        return retport

    def add_simulation(self, firesimservernode: FireSimServerNode) -> None:
        """ Add a simulation to the next available slot. """
        assert len(self.sim_slots) < self.MAX_SIM_SLOTS_ALLOWED
        self.sim_slots.append(firesimservernode)
        firesimservernode.assign_host_instance(self)

    def qcow2_support_required(self) -> bool:
        """ Return True iff any simulation on this Inst requires qcow2. """
        return any([x.qcow2_support_required() for x in self.sim_slots])

    def terminate_self(self) -> None:
        """ Terminate the current host for the Inst. """
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

    run_farm_hosts_dict: Dict[str, List[Tuple[Inst, Optional[Union[EC2InstanceResource, MockBoto3Instance]]]]]
    mapper_consumed: Dict[str, int]

    default_simulation_dir: str
    metasimulation_enabled: bool

    def __init__(self, args: Dict[str, Any], metasimulation_enabled: bool) -> None:
        self.args = args
        self.metasimulation_enabled = metasimulation_enabled
        self.default_simulation_dir = self.args.get("default_simulation_dir", "/home/centos")
        self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS = dict()
        self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS = dict()
        self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK = dict()


    def init_postprocess(self) -> None:
        self.SORTED_SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS = invert_filter_sort(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS)
        self.SORTED_SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS = invert_filter_sort(self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS)

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

        rootLogger.critical(f"ERROR: No hosts are available to satisfy the request for a host with support for {num_sims} simulation slots. Add more hosts in your run farm configuration (e.g., config_runtime.yaml).")
        raise Exception

    def allocate_sim_host(self, sim_host_handle: str) -> Inst:
        """Let user allocate and use an run host (assign sims, etc.) given it's handle."""
        inst_tup = self.run_farm_hosts_dict[sim_host_handle][self.mapper_consumed[sim_host_handle]]
        inst_ret = inst_tup[0]
        self.mapper_consumed[sim_host_handle] += 1
        return inst_ret

    def get_switch_only_host_handle(self) -> str:
        """Get the default run host handle (unique string to identify a run host type) that can
        host switch simulations.
        """
        for sim_host_handle, switch_ok in sorted(self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK.items(), key=lambda x: x[0]):
            if not switch_ok:
                # cannot use this handle for switch-only mapping
                continue

            num_consumed = self.mapper_consumed[sim_host_handle]
            num_allocated = len(self.run_farm_hosts_dict[sim_host_handle])
            if num_consumed >= num_allocated:
                # instance supports enough sims but none are available
                continue
            return sim_host_handle

        rootLogger.critical(f"ERROR: No hosts are available to satisfy the request for a host with support for running only switches. Add more hosts in your run farm configuration (e.g., config_runtime.yaml).")
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
    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
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
        run_farm_tag_prefix = "" if 'FIRESIM_RUNFARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUNFARM_PREFIX']
        if run_farm_tag_prefix != "":
            run_farm_tag_prefix += "-"

        self.run_farm_tag = run_farm_tag_prefix + self.args['run_farm_tag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.run_farm_tag = aws_resource_names_dict['runfarmprefix'] + "-" + self.run_farm_tag

        self.always_expand_run_farm = self.args['always_expand_run_farm']

        if 'launch_instances_timeout_minutes' in self.args:
            self.launch_timeout = timedelta(minutes=int(self.args['launch_instances_timeout_minutes']))
        else:
            self.launch_timeout = timedelta() # default to legacy behavior of not waiting

        self.run_instance_market = self.args['run_instance_market']
        self.spot_interruption_behavior = self.args['spot_interruption_behavior']
        self.spot_max_price = self.args['spot_max_price']

        dispatch_dict = dict([(x.__name__, x) for x in inheritors(InstanceDeployManager)])

        default_platform = "EC2InstanceDeployManager"

        runhost_specs = dict()
        for specinfo in self.args["run_farm_host_specs"]:
            specinfo_value = next(iter(specinfo.items()))
            runhost_specs[specinfo_value[0]] = specinfo_value[1]

        # populate mapping helpers based on runhost_specs:
        for runhost_spec_name, runhost_spec in runhost_specs.items():
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[runhost_spec_name] = runhost_spec['num_fpgas']
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[runhost_spec_name] = runhost_spec['num_metasims']
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[runhost_spec_name] = runhost_spec['use_for_switch_only']

        runhosts_list = self.args["run_farm_hosts_to_use"]

        self.run_farm_hosts_dict = defaultdict(list)
        self.mapper_consumed = defaultdict(int)

        for runhost in runhosts_list:
            if not isinstance(runhost, dict):
                raise Exception(f"Invalid runhost count definition for {runhost}.")

            items = runhost.items()
            assert (len(items) == 1), f"dict type 'runhost' items map a single EC2 instance name to a number. Not {pprint.pformat(runhost)}"

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
            simulation_dir = host_spec.get("override_simulation_dir", self.default_simulation_dir)

            insts: List[Tuple[Inst, Optional[Union[EC2InstanceResource, MockBoto3Instance]]]] = []
            for _ in range(num_insts):
                insts.append((Inst(self, num_sim_slots, dispatch_dict[platform], simulation_dir, self.metasimulation_enabled), None))
            self.run_farm_hosts_dict[inst_handle] = insts
            self.mapper_consumed[inst_handle] = 0


    def bind_mock_instances_to_objects(self) -> None:
        """ Only used for testing. Bind mock Boto3 instances to objects. """
        for inst_handle, inst_list in sorted(self.run_farm_hosts_dict.items(), key=lambda x: x[0]):
            for idx, run_farm_host_tup in enumerate(inst_list):
                boto_obj = MockBoto3Instance()
                inst = run_farm_host_tup[0]
                inst.set_host(boto_obj.private_ip_address)
                inst_list[idx] = (inst, boto_obj)
            self.run_farm_hosts_dict[inst_handle] = inst_list

    def bind_real_instances_to_objects(self) -> None:
        """ Attach running instances to the Run Farm. """
        # fetch instances based on tag,
        # populate IP addr list for use in the rest of our tasks.
        # we always sort by private IP when handling instances
        available_instances_per_handle = {}
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            available_instances_per_handle[sim_host_handle] = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(self.run_farm_tag, sim_host_handle))

        message = """Insufficient {}. Did you run `firesim launchrunfarm`?"""
        # confirm that we have the correct number of instances
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            if not (len(available_instances_per_handle[sim_host_handle]) >= len(self.run_farm_hosts_dict[sim_host_handle])):
                rootLogger.warning(message.format(sim_host_handle))

        ipmessage = """Using {} instances with IPs:\n{}"""
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            rootLogger.debug(ipmessage.format(sim_host_handle, str(get_private_ips_for_instances(available_instances_per_handle[sim_host_handle]))))

        # assign boto3 instance objects to our instance objects
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            for index, instance in enumerate(available_instances_per_handle[sim_host_handle]):
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
            expected_number_of_instances_of_handle = len(self.run_farm_hosts_dict[sim_host_handle])
            launched_instance_objs[sim_host_handle] = launch_run_instances(sim_host_handle, expected_number_of_instances_of_handle, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice, timeout, always_expand)

        # wait for instances to get to running state, so that they have been
        # assigned IP addresses
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            wait_on_instance_launches(launched_instance_objs[sim_host_handle], sim_host_handle)

    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
        runfarmtag = self.run_farm_tag

        # make sure requested instance handles are valid
        terminate_some_requested_handles_set = set(terminate_some_dict.keys())
        allowed_handles_set = set(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS)
        not_allowed_handles = terminate_some_requested_handles_set - allowed_handles_set
        if len(not_allowed_handles) != 0:
            # the terminatesome logic becomes messy if you have invalid instance
            # handles specified, so just exit and indicate error
            rootLogger.critical("WARNING: You have requested --terminatesome for the following invalid instance handles. Nothing has been terminated.\n" + str(not_allowed_handles))
            exit(1)

        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        all_instances = dict()
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            all_instances[sim_host_handle] = instances_sorted_by_avail_ip(
                get_run_instances_by_tag_type(runfarmtag, sim_host_handle))

        all_instance_ids = dict()
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            all_instance_ids[sim_host_handle] = get_instance_ids_for_instances(all_instances[sim_host_handle])

        if len(terminate_some_dict) != 0:
            # In this mode, only terminate instances that are specifically supplied.
            for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
                if sim_host_handle in terminate_some_dict and terminate_some_dict[sim_host_handle] > 0:
                    termcount = terminate_some_dict[sim_host_handle]
                    # grab the last N instances to terminate
                    all_instance_ids[sim_host_handle] = all_instance_ids[sim_host_handle][-termcount:]
                else:
                    all_instance_ids[sim_host_handle] = []

        rootLogger.critical("IMPORTANT!: This will terminate the following instances:")
        for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
            rootLogger.critical(sim_host_handle)
            rootLogger.critical(all_instance_ids[sim_host_handle])

        if not forceterminate:
            # --forceterminate was not supplied, so confirm with the user
            userconfirm = firesim_input("Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n")
        else:
            userconfirm = "yes"

        if userconfirm == "yes":
            for sim_host_handle in sorted(self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS):
                if len(all_instance_ids[sim_host_handle]) != 0:
                    terminate_instances(all_instance_ids[sim_host_handle], False)
            rootLogger.critical("Instances terminated. Please confirm in your AWS Management Console.")
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
        dispatch_dict = dict([(x.__name__, x) for x in inheritors(InstanceDeployManager)])

        default_platform = self.args.get("default_platform")

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

            assert (len(items) == 1), f"dict type 'run_hosts' items map a single host name to a host spec. Not: {pprint.pformat(runhost)}"

            ip_addr, host_spec_name = next(iter(items))

            if host_spec_name not in runhost_specs.keys():
                raise Exception(f"Unknown runhost spec of {host_spec_name}")

            host_spec = runhost_specs[host_spec_name]

            # populate mapping helpers based on runhost_specs:
            self.SIM_HOST_HANDLE_TO_MAX_FPGA_SLOTS[ip_addr] = host_spec['num_fpgas']
            self.SIM_HOST_HANDLE_TO_MAX_METASIM_SLOTS[ip_addr] = host_spec['num_metasims']
            self.SIM_HOST_HANDLE_TO_SWITCH_ONLY_OK[ip_addr] = host_spec['use_for_switch_only']

            num_sims = 0
            if self.metasimulation_enabled:
                num_sims = host_spec.get("num_metasims")
            else:
                num_sims = host_spec.get("num_fpgas")
            platform = host_spec.get("override_platform", default_platform)
            simulation_dir = host_spec.get("override_simulation_dir", self.default_simulation_dir)

            inst = Inst(self, num_sims, dispatch_dict[platform], simulation_dir, self.metasimulation_enabled)
            inst.set_host(ip_addr)
            assert not ip_addr in self.run_farm_hosts_dict, f"Duplicate host name found in 'run_farm_hosts': {ip_addr}"
            self.run_farm_hosts_dict[ip_addr] = [(inst, None)]
            self.mapper_consumed[ip_addr] = 0


    def post_launch_binding(self, mock: bool = False) -> None:
        return

    def launch_run_farm(self) -> None:
        rootLogger.info(f"WARNING: Skipping launchrunfarm since run hosts are externally provisioned.")
        return

    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
        rootLogger.info(f"WARNING: Skipping terminaterunfarm since run hosts are externally provisioned.")
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
        rootLogger.info(f"WARNING: Skipping terminate_by_inst since run hosts are externally provisioned.")
        return
