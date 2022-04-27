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
from util.streamlogger import StreamLogger
from util.inheritors import inheritors
from runtools.run_farm_deploy_managers import EC2InstanceDeployManager

from typing import Any, Dict, Optional, List, Union, Set, Type, Tuple, TYPE_CHECKING
if TYPE_CHECKING:
    from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode
    from runtools.run_farm_deploy_managers import InstanceDeployManager

rootLogger = logging.getLogger()

class Inst(metaclass=abc.ABCMeta):
    # switch variables
    # restricted by default security group network model port alloc (10000 to 11000)
    MAX_SWITCH_SLOTS_ALLOWED: int = 100000
    switch_slots: List[FireSimSwitchNode]
    _next_switch_port: int

    # simulation variables (normally corresponds with fpga sims)
    MAX_SIM_SLOTS_ALLOWED: int
    sim_slots: List[FireSimServerNode]

    sim_dir: Optional[str]

    # instances parameterized by this
    instance_deploy_manager: InstanceDeployManager

    def __init__(self, max_sim_slots_allowed: int, instance_deploy_manager: Type[InstanceDeployManager], sim_dir: Optional[str] = None) -> None:
        super().__init__()
        self.switch_slots = []
        self._next_switch_port = 10000 # track ports to allocate for server switch model ports

        self.MAX_SIM_SLOTS_ALLOWED = max_sim_slots_allowed
        self.sim_slots = []

        self.sim_dir = sim_dir

        self.instance_deploy_manager = instance_deploy_manager(self)

    def set_sim_dir(self, drctry: str) -> None:
        self.sim_dir = drctry

    def get_sim_dir(self) -> str:
        assert self.sim_dir is not None
        return self.sim_dir

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

class RunFarm(metaclass=abc.ABCMeta):
    def __init__(self, args: Dict[str, Any]) -> None:
        self.args = args

    @abc.abstractmethod
    def mapper_get_min_sim_host_inst_type_name(self, num_sims: int) -> str:
        """ Return the smallest instance type that supports greater than or
        equal to num_sims simulations AND has available instances of that type
        (according to instance counts you've specified in config_runtime.ini).
        """
        raise NotImplementedError

    @abc.abstractmethod
    def mapper_alloc_instance(self, instance_type_name: str) -> Inst:
        """ Let user allocate and use an instance (assign sims, etc.).
        This deliberately exposes instance_type_names to users, so that if
        they know exactly how to map to a particular platform, they always
        have an escape hatch. """
        raise NotImplementedError

    @abc.abstractmethod
    def mapper_get_default_switch_host_inst_type_name(self) -> str:
        """ Get the default host instance type name that can host switch
        simulations. """
        raise NotImplementedError

    @abc.abstractmethod
    def post_launch_binding(self, mock: bool = False) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def launch_run_farm(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def get_all_host_nodes(self) -> List[Inst]:
        raise NotImplementedError

    @abc.abstractmethod
    def get_all_bound_host_nodes(self) -> List[Inst]:
        raise NotImplementedError

    @abc.abstractmethod
    def lookup_by_ip_addr(self, ipaddr: str) -> Inst:
        raise NotImplementedError

class AWSEC2F1(RunFarm):
    """ This manages the set of AWS resources requested for the run farm. It
    essentially decouples launching instances from assigning them to simulations.

    This way, you can assign "instances" to simulations first, and then assign
    the real instance ids to the instance objects managed here."""
    run_farm_tag: str
    always_expand_runfarm: bool
    launch_timeout: timedelta
    run_instance_market: str
    spot_interruption_behavior: str
    spot_max_price: str
    default_simulation_dir: str

    SUPPORTED_INSTANCE_TYPE_NAMES: Set[str] = {
        'f1.16xlarge',
        'f1.4xlarge',
        'f1.2xlarge',
        'm4.16xlarge',
    }

    INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS: Dict[str, int] = {
        'f1.16xlarge': 8,
        'f1.4xlarge': 2,
        'f1.2xlarge': 1,
        'm4.16xlarge': 0,
    }

    INSTANCE_TYPE_NAME_FOR_SWITCH_ONLY_SIM: str = 'm4.16xlarge'

    SORTED_INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS: List[Tuple[int, str]]


    run_farm_hosts_dict: Dict[str, List[Inst]]
    mapper_consumed: Dict[str, int]

    def __init__(self, args: Dict[str, Any]) -> None:
        super().__init__(args)

        for inst_type in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            assert inst_type in self.INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS.keys()

        def invert_filter_sort(input_dict):
            """ take a dict, convert to list of pairs, flip key and value,
            remove all keys equal to zero, then sort on the new key. """
            out_list = [(y, x) for x, y in list(input_dict.items())]
            out_list = list(filter(lambda x: x[0] != 0, out_list))
            return sorted(out_list, key=lambda x: x[0])

        # for later use during mapping
        self.SORTED_INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS = invert_filter_sort(self.INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS)

        self._parse_args()

    def _parse_args(self) -> None:
        run_farm_tag_prefix = "" if 'FIRESIM_RUN_FARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUN_FARM_PREFIX']
        if run_farm_tag_prefix != "":
            run_farm_tag_prefix += "-"

        self.run_farm_tag = run_farm_tag_prefix + self.args['run_farm_tag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.run_farm_tag = aws_resource_names_dict['runfarmprefix'] + "-" + self.run_farm_tag

        self.always_expand_runfarm = self.args['always_expand_runfarm']

        if 'launch_instances_timeout_minutes' in self.args:
            self.launch_timeout = timedelta(minutes=int(self.args['launch_instances_timeout_minutes']))
        else:
            self.launch_timeout = timedelta() # default to legacy behavior of not waiting

        self.run_instance_market = self.args['run_instance_market']
        self.spot_interruption_behavior = self.args['spot_interruption_behavior']
        self.spot_max_price = self.args['spot_max_price']

        self.default_simulation_dir = self.args["default_simulation_dir"]

        runhosts_list = self.args["run_farm_hosts"]

        self.run_farm_hosts_dict = defaultdict(list)

        for runhost in runhosts_list:
            if isinstance(runhost, dict):
                # add element { NAME: int }

                items = runhost.items()

                assert (len(items) == 1), f"dict type 'runhost' items map a single EC2 instance name to a number. Not {pprint.pformat(runhost)}"

                inst_type, num_insts = next(iter(items))

                if inst_type in self.SUPPORTED_INSTANCE_TYPE_NAMES:
                    num_sim_slots = self.INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS[inst_type]
                    inst = Inst(num_sim_slots, EC2InstanceDeployManager, self.default_simulation_dir)
                    self.run_farm_hosts_dict[inst_type].append(inst)
                    self.mapper_consumed[inst_type] = 0
                else:
                    rootLogger.critical(f"WARNING: Skipping {inst_type} since it is not supported. Use {self.SUPPORTED_INSTANCE_TYPE_NAMES}.")
            else:
                raise Exception(f"Unknown runhost type of {runhost}")

    def mapper_get_min_sim_host_inst_type_name(self, num_sims: int) -> str:
        """ Return the smallest instance type that supports greater than or
        equal to num_sims simulations AND has available instances of that type
        (according to instance counts you've specified in config_runtime.ini).
        """

        for max_simcount, instance_type_name in self.SORTED_INSTANCE_TYPE_NAME_TO_MAX_FPGA_SLOTS:
            if max_simcount < num_sims:
                # instance doesn't support enough sims
                continue
            num_consumed = self.mapper_consumed[instance_type_name]
            num_allocated = len(self.run_farm_hosts_dict[instance_type_name])
            if num_consumed >= num_allocated:
                # instance supports enough sims but none are available
                continue
            return instance_type_name

        rootLogger.critical("ERROR: No instances are available to satisfy the request for an instance with support for " + str(num_sims) + " simulation slots. Add more instances in your runtime configuration (e.g., config_runtime.ini).")
        raise Exception

    def mapper_alloc_instance(self, instance_type_name: str) -> Inst:
        """ Let user allocate and use an instance (assign sims, etc.).
        This deliberately exposes instance_type_names to users, so that if
        they know exactly how to map to a particular platform, they always
        have an escape hatch. """
        inst_ret = self.run_farm_hosts_dict[instance_type_name][self.mapper_consumed[instance_type_name]]
        self.mapper_consumed[instance_type_name] += 1
        return inst_ret

    def mapper_get_default_switch_host_inst_type_name(self) -> str:
        """ Get the default host instance type name that can host switch
        simulations. """
        return self.INSTANCE_TYPE_NAME_FOR_SWITCH_ONLY_SIM

    def bind_mock_instances_to_objects(self) -> None:
        """ Only used for testing. Bind mock Boto3 instances to objects. """
        for inst_type, inst_list in self.run_farm_hosts_dict.items():
            for run_farm_host in inst_list:
                assert isinstance(run_farm_host.instance_deploy_manager, EC2InstanceDeployManager)
                run_farm_host.instance_deploy_manager.boto3_instance_object = MockBoto3Instance()

    def bind_real_instances_to_objects(self) -> None:
        """ Attach running instances to the Run Farm. """
        # fetch instances based on tag,
        # populate IP addr list for use in the rest of our tasks.
        # we always sort by private IP when handling instances
        available_instances_per_type = {}
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            available_instances_per_type[instance_type_name] = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(self.run_farm_tag, instance_type_name))

        message = """Insufficient {}. Did you run `firesim launchrunfarm`?"""
        # confirm that we have the correct number of instances
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            if not (len(available_instances_per_type[instance_type_name]) >= len(self.run_farm_hosts_dict[instance_type_name])):
                rootLogger.warning(message.format(instance_type_name))


        ipmessage = """Using {} instances with IPs:\n{}"""
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            rootLogger.debug(ipmessage.format(instance_type_name, str(get_private_ips_for_instances(available_instances_per_type[instance_type_name]))))

        # assign boto3 instance objects to our instance objects
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            for index, instance in enumerate(available_instances_per_type[instance_type_name]):
                inst = self.run_farm_hosts_dict[instance_type_name][index]
                assert isinstance(inst.instance_deploy_manager, EC2InstanceDeployManager)
                inst.instance_deploy_manager.boto3_instance_object = instance

    def post_launch_binding(self, mock: bool = False) -> None:
        if mock:
            self.bind_mock_instances_to_objects()
        else:
            self.bind_real_instances_to_objects()

    def launch_run_farm(self) -> None:
        """ Launch the run farm. """

        runfarmtag = self.run_farm_tag
        runinstancemarket = self.run_instance_market
        spotinterruptionbehavior = self.spot_interruption_behavior
        spotmaxprice = self.spot_max_price
        timeout = self.launch_timeout
        always_expand = self.always_expand_runfarm

        # actually launch the instances
        launched_instance_objs = {}
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            expected_number_of_instances_of_type = len(self.run_farm_hosts_dict[instance_type_name])
            launched_instance_objs[instance_type_name] = launch_run_instances(instance_type_name, expected_number_of_instances_of_type, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice, timeout, always_expand)

        # wait for instances to get to running state, so that they have been
        # assigned IP addresses
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            wait_on_instance_launches(launched_instance_objs[instance_type_name], instance_type_name)

    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
        runfarmtag = self.run_farm_tag

        # make sure requested instance types are valid
        terminate_some_requested_types_set = set(terminate_some_dict.keys())
        allowed_types_set = set(self.SUPPORTED_INSTANCE_TYPE_NAMES)
        not_allowed_types = terminate_some_requested_types_set - allowed_types_set
        if len(not_allowed_types) != 0:
            # the terminatesome logic becomes messy if you have invalid instance
            # types specified, so just exit and indicate error
            rootLogger.critical("WARNING: You have requested --terminatesome for the following invalid instance types. Nothing has been terminated.\n" + str(not_allowed_types))
            exit(1)

        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        all_instances = dict()
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            all_instances[instance_type_name] = instances_sorted_by_avail_ip(
            get_run_instances_by_tag_type(runfarmtag, instance_type_name))

        all_instance_ids = dict()
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            all_instance_ids[instance_type_name] = get_instance_ids_for_instances(all_instances[instance_type_name])

        if len(terminate_some_dict) != 0:
            # In this mode, only terminate instances that are specifically supplied.
            for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
                if instance_type_name in terminate_some_dict and terminate_some_dict[instance_type_name] > 0:
                    termcount = terminate_some_dict[instance_type_name]
                    # grab the last N instances to terminate
                    all_instance_ids[instance_type_name] = all_instance_ids[instance_type_name][-termcount:]
                else:
                    all_instance_ids[instance_type_name] = []

        rootLogger.critical("IMPORTANT!: This will terminate the following instances:")
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            rootLogger.critical(instance_type_name)
            rootLogger.critical(all_instance_ids[instance_type_name])

        if not forceterminate:
            # --forceterminate was not supplied, so confirm with the user
            userconfirm = input("Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n")
        else:
            userconfirm = "yes"

        if userconfirm == "yes":
            for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
                if len(all_instance_ids[instance_type_name]) != 0:
                    terminate_instances(all_instance_ids[instance_type_name], False)
            rootLogger.critical("Instances terminated. Please confirm in your AWS Management Console.")
        else:
            rootLogger.critical("Termination cancelled.")

    def get_all_host_nodes(self) -> List[Inst]:
        all_insts = []
        for instance_type_name in self.SUPPORTED_INSTANCE_TYPE_NAMES:
            inst_list = self.run_farm_hosts_dict[instance_type_name]
            for inst in inst_list:
                assert isinstance(inst.instance_deploy_manager, EC2InstanceDeployManager)
                all_insts.append(inst)
        return all_insts

    def get_all_bound_host_nodes(self) -> List[Inst]:
        all_insts = []
        for inst in self.get_all_host_nodes():
            assert isinstance(inst.instance_deploy_manager, EC2InstanceDeployManager)
            if inst.instance_deploy_manager.boto3_instance_object is not None:
                all_insts.append(inst)
        return all_insts

    def lookup_by_ip_addr(self, ipaddr) -> Inst:
        """ Get an instance object from its IP address. """
        for host_node in self.get_all_bound_host_nodes():
            if host_node.instance_deploy_manager.get_hostname() == ipaddr:
                return host_node
        assert False, f"Unable to find host node by {ipaddr} host name"

class ExternallyProvisioned(RunFarm):
    """ This manages the set of AWS resources requested for the run farm. It
    essentially decouples launching instances from assigning them to simulations.

    This way, you can assign "instances" to simulations first, and then assign
    the real instance ids to the instance objects managed here."""
    fpga_nodes: List[Inst]

    def __init__(self, args: Dict[str, Any]) -> None:
        super().__init__(args)

        self._parse_args()

    def _parse_args(self) -> None:
        dispatch_dict = dict([(x.__name__, x) for x in inheritors(InstanceDeployManager)])

        default_num_fpgas = self.args.get("default_num_fpgas")
        default_platform = self.args.get("default_platform")
        default_simulation_dir = self.args.get("default_simulation_dir")

        runhosts_list = self.args["run_farm_hosts"]

        self.fpga_nodes = []

        # TODO: currently, only supports 1 ip address
        assert(len(runhosts_list) == 1)

        for runhost in runhosts_list:
            if isinstance(runhost, dict):
                # add element { ip-addr: { arg1: val1, arg2: val2, ... } }

                items = runhost.items()

                assert (len(items) == 1), f"dict type 'run_hosts' items map a single host name to a dict of options. Not: {pprint.pformat(runhost)}"

                ip_addr, ip_args = next(iter(items))

                num_fpgas = ip_args.get("override_num_fpgas", default_num_fpgas)
                platform = ip_args.get("override_platform", default_platform)
                simulation_dir = ip_args.get("override_simulation_dir", default_simulation_dir)

                inst = Inst(num_fpgas, dispatch_dict[platform], simulation_dir)
                inst.instance_deploy_manager.set_hostname(ip_addr)
                self.fpga_nodes.append(inst)
            elif isinstance(runhost, str):
                # add element w/ defaults
                assert default_num_fpgas is not None and isinstance(default_num_fpgas, int)
                assert default_platform is not None and isinstance(default_platform, str)
                assert default_simulation_dir is not None and isinstance(default_simulation_dir, str)
                inst = Inst(default_num_fpgas, dispatch_dict[default_platform], default_simulation_dir)
                inst.instance_deploy_manager.set_hostname(runhost)
                self.fpga_nodes.append(inst)
            else:
                raise Exception("Unknown runhost type")

    def mapper_get_min_sim_host_inst_type_name(self, num_sims: int) -> str:
        """ Return the smallest instance type that supports greater than or
        equal to num_sims simulations AND has available instances of that type
        (according to instance counts you've specified in config_runtime.ini).
        """
        raise NotImplementedError

    def mapper_alloc_instance(self, instance_type_name: str) -> Inst:
        """ Let user allocate and use an instance (assign sims, etc.).
        This deliberately exposes instance_type_names to users, so that if
        they know exactly how to map to a particular platform, they always
        have an escape hatch. """
        raise NotImplementedError

    def mapper_get_default_switch_host_inst_type_name(self) -> str:
        """ Get the default host instance type name that can host switch
        simulations. """
        raise NotImplementedError

    def post_launch_binding(self, mock: bool = False) -> None:
        return

    def launch_run_farm(self) -> None:
        return

    def terminate_run_farm(self, terminate_some_dict: Dict[str, int], forceterminate: bool) -> None:
        return

    def get_all_host_nodes(self) -> List[Inst]:
        return self.fpga_nodes

    def get_all_bound_host_nodes(self) -> List[Inst]:
        return self.get_all_host_nodes()

    def lookup_by_ip_addr(self, ipaddr: str) -> Inst:
        """ Get an instance object from its IP address. """
        for host_node in self.get_all_bound_host_nodes():
            if host_node.instance_deploy_manager.get_hostname() == ipaddr:
                return host_node
        assert False, f"Unable to find host node by {ipaddr} host name"

