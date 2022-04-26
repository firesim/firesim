from __future__ import annotations

from __future__ import  annotations

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

from runtools.run_farm_instances import MockBoto3Instance, M4_16, F1Inst, Inst, FPGAInst
from awstools.awstools import instances_sorted_by_avail_ip, get_run_instances_by_tag_type, get_private_ips_for_instances, launch_run_instances, wait_on_instance_launches, terminate_instances, get_instance_ids_for_instances, aws_resource_names
from util.streamlogger import StreamLogger
from util.inheritors import inheritors

from typing import Any, Dict, Optional, List, Union, TYPE_CHECKING
if TYPE_CHECKING:
    from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode

rootLogger = logging.getLogger()

class RunFarm(metaclass=abc.ABCMeta):
    def __init__(self, args: Dict[str, Any]) -> None:
        self.args = args

    @abc.abstractmethod
    def post_launch_binding(self, mock: bool = False) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def launch_run_farm(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_run_farm(self, terminatesomef1_16: int, terminatesomef1_4: int, terminatesomef1_2: int,
            terminatesomem4_16: int, forceterminate: bool) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def get_all_host_nodes(self) -> List[Inst]:
        raise NotImplementedError

    @abc.abstractmethod
    def lookup_by_ip_addr(self, ipaddr: str) -> Optional[Inst]:
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
    f1_16s: List[F1Inst]
    f1_4s: List[F1Inst]
    f1_2s: List[F1Inst]
    m4_16s: List[M4_16]
    default_simulation_dir: str

    def __init__(self, args: Dict[str, Any]) -> None:
        super().__init__(args)

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

        num_f1_16 = self.args['f1_16xlarges']
        num_f1_4 = self.args['f1_4xlarges']
        num_m4_16 = self.args['m4_16xlarges']
        num_f1_2 = self.args['f1_2xlarges']

        if 'launch_instances_timeout_minutes' in self.args:
            self.launch_timeout = timedelta(minutes=int(self.args['launch_instances_timeout_minutes']))
        else:
            self.launch_timeout = timedelta() # default to legacy behavior of not waiting

        self.run_instance_market = self.args['run_instance_market']
        self.spot_interruption_behavior = self.args['spot_interruption_behavior']
        self.spot_max_price = self.args['spot_max_price']

        self.default_simulation_dir = self.args["default_simulation_dir"]

        self.f1_16s = [F1Inst(8) for x in range(num_f1_16)]
        self.f1_4s = [F1Inst(2) for x in range(num_f1_4)]
        self.f1_2s = [F1Inst(1) for x in range(num_f1_2)]
        self.m4_16s = [M4_16() for x in range(num_m4_16)]

        for node in [*self.f1_16s, *self.f1_2s, *self.f1_4s, *self.m4_16s]:
            node.set_sim_dir(self.default_simulation_dir)

    def bind_mock_instances_to_objects(self) -> None:
        """ Only used for testing. Bind mock Boto3 instances to objects. """
        for index in range(len(self.f1_16s)):
            self.f1_16s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.f1_4s)):
            self.f1_4s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.f1_2s)):
            self.f1_2s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.m4_16s)):
            self.m4_16s[index].assign_boto3_instance_object(MockBoto3Instance())

    def bind_real_instances_to_objects(self) -> None:
        """ Attach running instances to the Run Farm. """
        # fetch instances based on tag,
        # populate IP addr list for use in the rest of our tasks.
        # we always sort by private IP when handling instances
        available_f1_16_instances = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(
            self.run_farm_tag, 'f1.16xlarge'))
        available_f1_4_instances = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(
            self.run_farm_tag, 'f1.4xlarge'))
        available_m4_16_instances = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(
            self.run_farm_tag, 'm4.16xlarge'))
        available_f1_2_instances = instances_sorted_by_avail_ip(get_run_instances_by_tag_type(
            self.run_farm_tag, 'f1.2xlarge'))

        message = """Insufficient {}. Did you run `firesim launchrunfarm`?"""
        # confirm that we have the correct number of instances
        if not (len(available_f1_16_instances) >= len(self.f1_16s)):
            rootLogger.warning(message.format("f1.16xlarges"))
        if not (len(available_f1_4_instances) >= len(self.f1_4s)):
            rootLogger.warning(message.format("f1.4xlarges"))
        if not (len(available_f1_2_instances) >= len(self.f1_2s)):
            rootLogger.warning(message.format("f1.2xlarges"))
        if not (len(available_m4_16_instances) >= len(self.m4_16s)):
            rootLogger.warning(message.format("m4.16xlarges"))

        ipmessage = """Using {} instances with IPs:\n{}"""
        rootLogger.debug(ipmessage.format("f1.16xlarge", str(get_private_ips_for_instances(available_f1_16_instances))))
        rootLogger.debug(ipmessage.format("f1.4xlarge", str(get_private_ips_for_instances(available_f1_4_instances))))
        rootLogger.debug(ipmessage.format("f1.2xlarge", str(get_private_ips_for_instances(available_f1_2_instances))))
        rootLogger.debug(ipmessage.format("m4.16xlarge", str(get_private_ips_for_instances(available_m4_16_instances))))

        # assign boto3 instance objects to our instance objects
        for index, instance in enumerate(available_f1_16_instances):
            self.f1_16s[index].assign_boto3_instance_object(instance)

        for index, instance in enumerate(available_f1_4_instances):
            self.f1_4s[index].assign_boto3_instance_object(instance)

        for index, instance in enumerate(available_m4_16_instances):
            self.m4_16s[index].assign_boto3_instance_object(instance)

        for index, instance in enumerate(available_f1_2_instances):
            self.f1_2s[index].assign_boto3_instance_object(instance)

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

        num_f1_16xlarges = len(self.f1_16s)
        num_f1_4xlarges = len(self.f1_4s)
        num_f1_2xlarges = len(self.f1_2s)
        num_m4_16xlarges = len(self.m4_16s)

        # actually launch the instances
        f1_16s = launch_run_instances('f1.16xlarge', num_f1_16xlarges, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice, timeout, always_expand)
        f1_4s = launch_run_instances('f1.4xlarge', num_f1_4xlarges, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice, timeout, always_expand)
        m4_16s = launch_run_instances('m4.16xlarge', num_m4_16xlarges, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice, timeout, always_expand)
        f1_2s = launch_run_instances('f1.2xlarge', num_f1_2xlarges, runfarmtag,
                                     runinstancemarket, spotinterruptionbehavior,
                                     spotmaxprice, timeout, always_expand)

        # wait for instances to finish launching
        # TODO: maybe we shouldn't do this, but just let infrasetup block. That
        # way we get builds out of the way while waiting for instances to launch
        wait_on_instance_launches(f1_16s, 'f1.16xlarges')
        wait_on_instance_launches(f1_4s, 'f1.4xlarges')
        wait_on_instance_launches(m4_16s, 'm4.16xlarges')
        wait_on_instance_launches(f1_2s, 'f1.2xlarges')


    def terminate_run_farm(self, terminatesomef1_16: int, terminatesomef1_4: int, terminatesomef1_2: int,
            terminatesomem4_16: int, forceterminate: bool) -> None:
        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        f1_16_instances = instances_sorted_by_avail_ip(
            get_run_instances_by_tag_type(self.run_farm_tag, 'f1.16xlarge'))
        f1_4_instances = instances_sorted_by_avail_ip(
            get_run_instances_by_tag_type(self.run_farm_tag, 'f1.4xlarge'))
        m4_16_instances = instances_sorted_by_avail_ip(
            get_run_instances_by_tag_type(self.run_farm_tag, 'm4.16xlarge'))
        f1_2_instances = instances_sorted_by_avail_ip(
            get_run_instances_by_tag_type(self.run_farm_tag, 'f1.2xlarge'))

        f1_16_instance_ids = get_instance_ids_for_instances(f1_16_instances)
        f1_4_instance_ids = get_instance_ids_for_instances(f1_4_instances)
        m4_16_instance_ids = get_instance_ids_for_instances(m4_16_instances)
        f1_2_instance_ids = get_instance_ids_for_instances(f1_2_instances)

        argsupplied_f116 = terminatesomef1_16 != -1
        argsupplied_f14 = terminatesomef1_4 != -1
        argsupplied_f12 = terminatesomef1_2 != -1
        argsupplied_m416 = terminatesomem4_16 != -1

        if argsupplied_f116 or argsupplied_f14 or argsupplied_f12 or argsupplied_m416:
            # In this mode, only terminate instances that are specifically supplied.
            if argsupplied_f116 and terminatesomef1_16 != 0:
                # grab the last N instances to terminate
                f1_16_instance_ids = f1_16_instance_ids[-terminatesomef1_16:]
            else:
                f1_16_instance_ids = []

            if argsupplied_f14 and terminatesomef1_4 != 0:
                # grab the last N instances to terminate
                f1_4_instance_ids = f1_4_instance_ids[-terminatesomef1_4:]
            else:
                f1_4_instance_ids = []

            if argsupplied_f12 and terminatesomef1_2 != 0:
                # grab the last N instances to terminate
                f1_2_instance_ids = f1_2_instance_ids[-terminatesomef1_2:]
            else:
                f1_2_instance_ids = []

            if argsupplied_m416 and terminatesomem4_16 != 0:
                # grab the last N instances to terminate
                m4_16_instance_ids = m4_16_instance_ids[-terminatesomem4_16:]
            else:
                m4_16_instance_ids = []

        rootLogger.critical("IMPORTANT!: This will terminate the following instances:")
        rootLogger.critical("f1.16xlarges")
        rootLogger.critical(f1_16_instance_ids)
        rootLogger.critical("f1.4xlarges")
        rootLogger.critical(f1_4_instance_ids)
        rootLogger.critical("m4.16xlarges")
        rootLogger.critical(m4_16_instance_ids)
        rootLogger.critical("f1.2xlarges")
        rootLogger.critical(f1_2_instance_ids)

        if not forceterminate:
            # --forceterminate was not supplied, so confirm with the user
            userconfirm = input("Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n")
        else:
            userconfirm = "yes"

        if userconfirm == "yes":
            if len(f1_16_instance_ids) != 0:
                terminate_instances(f1_16_instance_ids, False)
            if len(f1_4_instance_ids) != 0:
                terminate_instances(f1_4_instance_ids, False)
            if len(m4_16_instance_ids) != 0:
                terminate_instances(m4_16_instance_ids, False)
            if len(f1_2_instance_ids) != 0:
                terminate_instances(f1_2_instance_ids, False)
            rootLogger.critical("Instances terminated. Please confirm in your AWS Management Console.")
        else:
            rootLogger.critical("Termination cancelled.")

    def get_all_host_nodes(self) -> List[Inst]:
        return [*self.f1_16s, *self.f1_2s, *self.f1_4s, *self.m4_16s]

    def get_all_bound_host_nodes(self) -> List[Inst]:
        return [inst for inst in self.get_all_host_nodes() if inst.is_bound_to_real_instance()]

    def lookup_by_ip_addr(self, ipaddr) -> Optional[Inst]:
        """ Get an instance object from its IP address. """
        for host_node in self.get_all_bound_host_nodes():
            if host_node.get_ip() == ipaddr:
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
        dispatch_dict = dict([(x.NAME, x) for x in inheritors(FPGAInst)])

        default_num_fpgas = self.args.get("default_num_fpgas")
        default_platform = self.args.get("default_platform")
        default_simulation_dir = self.args.get("default_simulation_dir")

        runhosts_list = self.args["run_hosts"]

        self.fpga_nodes = []

        # TODO: currently, only supports 1 ip address
        assert(len(runhosts_list) == 1)

        for runhost in runhosts_list:
            if type(runhost) is dict:
                # add element { ip-addr: { arg1: val1, arg2: val2, ... } }

                items = runhost.items()

                assert (len(items) == 1), f"dict type 'run_hosts' items map a single host name to a dict of options. Not: {pprint.pformat(runhost)}"

                ip_addr, ip_args = next(iter(items))

                num_fpgas = ip_args.get("override_num_fpgas", default_num_fpgas)
                platform = ip_args.get("override_platform", default_platform)
                simulation_dir = ip_args.get("override_simulation_dir", default_simulation_dir)

                fpga_node = dispatch_dict[platform](num_fpgas)
                fpga_node.set_ip(ip_addr)
                fpga_node.set_sim_dir(simulation_dir)

                self.fpga_nodes.append(fpga_node)
            elif type(runhost) is str:
                # add element w/ defaults
                fpga_node = dispatch_dict[default_platform](default_num_fpgas)

                fpga_node.set_ip(runhost)
                fpga_node.set_sim_dir(default_simulation_dir)

                self.fpga_nodes.append(fpga_node)
            else:
                raise Exception("Unknown runhost type")

    def post_launch_binding(self, mock: bool = False) -> None:
        return

    def launch_run_farm(self) -> None:
        return

    def terminate_run_farm(self, terminatesomef1_16: int, terminatesomef1_4: int, terminatesomef1_2: int,
            terminatesomem4_16: int, forceterminate: bool) -> None:
        return

    def get_all_host_nodes(self) -> List[Inst]:
        return self.fpga_nodes

    def get_all_bound_host_nodes(self) -> List[Inst]:
        return self.get_all_host_nodes()

    def lookup_by_ip_addr(self, ipaddr: str) -> Inst:
        """ Get an instance object from its IP address. """
        for host_node in self.get_all_bound_host_nodes():
            if host_node.get_ip() == ipaddr:
                return host_node
        assert False, f"Unable to find host node by {ipaddr} host name"

