""" Run Farm management. """

import re
import logging

from awstools.awstools import *
from fabric.api import *
from fabric.contrib.project import rsync_project
from util.streamlogger import StreamLogger
import time
from importlib import import_module

rootLogger = logging.getLogger()

class MockBoto3Instance:
    """ This is used for testing without actually launching instances. """

    # don't use 0 unless you want stuff copied to your own instance.
    base_ip = 1

    def __init__(self):
        self.ip_addr_int = MockBoto3Instance.base_ip
        MockBoto3Instance.base_ip += 1
        self.private_ip_address = ".".join([str((self.ip_addr_int >> (8*x)) & 0xFF) for x in [3, 2, 1, 0]])


class RunFarm:
    def __init__(self, arg_dict):
        """ Initialization function.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            arg_dict (dict): Dict of args (i.e. options) passed to the dispatcher
        """

        self.arg_dict = arg_dict

    def parse_args(self):
        """ Parse default build host arguments. Can be overridden by child classes. """
        return

    def get_arg(self, arg_wanted):
        """ Retrieve argument from arg dict and error if not found.

        Parameters:
            arg_wanted (str): Argument to get value of
        Returns:
            (str or None): Value of argument wanted
        """
        if not self.arg_dict.has_key(arg_wanted):
            rootLogger.critical("ERROR: Unable to find arg {} for {}".format(arg_wanted, self.__name__))
            sys.exit(1)
        return self.arg_dict.get(arg_wanted)

    def post_launch_binding(self, mock = False):
        raise NotImplementedError

    def launch_run_farm(self):
        raise NotImplementedError

    def terminate_run_farm(self):
        raise NotImplementedError

    def get_all_host_nodes(self):
        raise NotImplementedError

    def lookup_by_ip_addr(self, ipaddr):
        raise NotImplementedError

    def pass_no_net_host_mapping(self, firesim_topology):
        raise NotImplementedError

    def pass_simple_networked_host_node_mapping(self, firesim_topology):
        raise NotImplementedError

class EC2RunFarm(RunFarm):
    """ This manages the set of AWS resources requested for the run farm. It
    essentially decouples launching instances from assigning them to simulations.

    This way, you can assign "instances" to simulations first, and then assign
    the real instance ids to the instance objects managed here."""

    def __init__(self, arg_dict):
        RunFarm.__init__(self, arg_dict)

        self.f1_16s = []
        self.f1_4s = []
        self.f1_2s = []
        self.m4_16s = []

        self.runfarmtag = None
        self.run_instance_market = None
        self.spot_interruption_behavior = None
        self.spot_max_price = None

    def parse_args(self):
        runfarmtagprefix = "" if 'FIRESIM_RUNFARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUNFARM_PREFIX']
        if runfarmtagprefix != "":
            runfarmtagprefix += "-"

        self.runfarmtag = runfarmtagprefix + self.get_arg('runfarmtag')

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.runfarmtag = aws_resource_names_dict['runfarmprefix'] + "-" + self.runfarmtag

        num_f1_16 = self.get_arg('f1_16xlarges')
        num_f1_4 = self.get_arg('f1_4xlarges')
        num_m4_16 = self.get_arg('m4_16xlarges')
        num_f1_2 = self.get_arg('f1_2xlarges')

        self.run_instance_market = self.get_arg('runinstancemarket')
        self.spot_interruption_behavior = self.get_arg('spotinterruptionbehavior')
        self.spot_max_price = self.get_arg('spotmaxprice')

        self.f1_16s = [F1_16() for x in range(num_f1_16)]
        self.f1_4s = [F1_4() for x in range(num_f1_4)]
        self.f1_2s = [F1_2() for x in range(num_f1_2)]
        self.m4_16s = [M4_16() for x in range(num_m4_16)]

    def bind_mock_instances_to_objects(self):
        """ Only used for testing. Bind mock Boto3 instances to objects. """
        for index in range(len(self.f1_16s)):
            self.f1_16s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.f1_4s)):
            self.f1_4s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.f1_2s)):
            self.f1_2s[index].assign_boto3_instance_object(MockBoto3Instance())

        for index in range(len(self.m4_16s)):
            self.m4_16s[index].assign_boto3_instance_object(MockBoto3Instance())

    def post_launch_binding(self, mock = False):
        """ Attach running instances to the Run Farm. """

        if mock:
            self.bind_mock_instances_to_objects()
            return

        # fetch instances based on tag,
        # populate IP addr list for use in the rest of our tasks.
        # we always sort by private IP when handling instances
        available_f1_16_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.runfarmtag, 'f1.16xlarge'))
        available_f1_4_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.runfarmtag, 'f1.4xlarge'))
        available_m4_16_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.runfarmtag, 'm4.16xlarge'))
        available_f1_2_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.runfarmtag, 'f1.2xlarge'))

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


    def launch_run_farm(self):
        """ Launch the run farm. """

        runfarmtag = self.runfarmtag
        runinstancemarket = self.run_instance_market
        spotinterruptionbehavior = self.spot_interruption_behavior
        spotmaxprice = self.spot_max_price

        num_f1_16xlarges = len(self.f1_16s)
        num_f1_4xlarges = len(self.f1_4s)
        num_f1_2xlarges = len(self.f1_2s)
        num_m4_16xlarges = len(self.m4_16s)

        # actually launch the instances
        f1_16s = launch_run_instances('f1.16xlarge', num_f1_16xlarges, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice)
        f1_4s = launch_run_instances('f1.4xlarge', num_f1_4xlarges, runfarmtag,
                                     runinstancemarket, spotinterruptionbehavior,
                                     spotmaxprice)
        m4_16s = launch_run_instances('m4.16xlarge', num_m4_16xlarges, runfarmtag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice)
        f1_2s = launch_run_instances('f1.2xlarge', num_f1_2xlarges, runfarmtag,
                                     runinstancemarket, spotinterruptionbehavior,
                                     spotmaxprice)

        # wait for instances to finish launching
        # TODO: maybe we shouldn't do this, but just let infrasetup block. That
        # way we get builds out of the way while waiting for instances to launch
        wait_on_instance_launches(f1_16s, 'f1.16xlarges')
        wait_on_instance_launches(f1_4s, 'f1.4xlarges')
        wait_on_instance_launches(m4_16s, 'm4.16xlarges')
        wait_on_instance_launches(f1_2s, 'f1.2xlarges')


    def terminate_run_farm(self, terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                           terminatesomem4_16, forceterminate):
        runfarmtag = self.runfarmtag

        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        f1_16_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(runfarmtag, 'f1.16xlarge'))
        f1_4_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(runfarmtag, 'f1.4xlarge'))
        m4_16_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(runfarmtag, 'm4.16xlarge'))
        f1_2_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(runfarmtag, 'f1.2xlarge'))

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
            userconfirm = raw_input("Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.\n")
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

    def get_all_host_nodes(self):
        """ Get inst objects for all host nodes in the run farm that are bound to
        a real instance. """
        allinsts = self.f1_16s + self.f1_2s + self.f1_4s + self.m4_16s
        return [inst for inst in allinsts if inst.boto3_instance_object is not None]

    def lookup_by_ip_addr(self, ipaddr):
        """ Get an instance object from its IP address. """
        for host_node in self.get_all_host_nodes():
            if host_node.get_ip() == ipaddr:
                return host_node
        return None

    def pass_no_net_host_mapping(self, firesim_topology):
        # only if we have no networks - pack simulations
        # assumes the user has provided enough or more slots
        servers = firesim_topology.get_dfs_order_servers()
        serverind = 0

        for f1_16x in self.f1_16s:
            for x in range(f1_16x.get_num_fpga_slots_max()):
                f1_16x.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return
        for f1_4x in self.f1_4s:
            for x in range(f1_4x.get_num_fpga_slots_max()):
                f1_4x.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return
        for f1_2x in self.f1_2s:
            for x in range(f1_2x.get_num_fpga_slots_max()):
                f1_2x.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return
        assert serverind == len(servers), "ERR: all servers were not assigned to a host."

    def pass_simple_networked_host_node_mapping(self, firesim_topology):
        """ A very simple host mapping strategy.  """
        switches = firesim_topology.get_dfs_order_switches()
        f1_2s_used = 0
        f1_4s_used = 0
        f1_16s_used = 0
        m4_16s_used = 0

        for switch in switches:
            # Filter out FireSimDummyServerNodes for actually deploying.
            # Infrastructure after this point will automatically look at the
            # FireSimDummyServerNodes if a FireSimSuperNodeServerNode is used
            downlinknodes = map(lambda x: x.get_downlink_side(), [downlink for downlink in switch.downlinks if not isinstance(downlink.get_downlink_side(), FireSimDummyServerNode)])
            if all([isinstance(x, FireSimSwitchNode) for x in downlinknodes]):
                # all downlinks are switches
                self.m4_16s[m4_16s_used].add_switch(switch)
                m4_16s_used += 1
            elif all([isinstance(x, FireSimServerNode) for x in downlinknodes]):
                # all downlinks are simulations
                if (len(downlinknodes) == 1) and (f1_2s_used < len(self.f1_2s)):
                    self.f1_2s[f1_2s_used].add_switch(switch)
                    self.f1_2s[f1_2s_used].add_simulation(downlinknodes[0])
                    f1_2s_used += 1
                elif (len(downlinknodes) <= 2) and (f1_4s_used < len(self.f1_4s)):
                    self.f1_4s[f1_4s_used].add_switch(switch)
                    for server in downlinknodes:
                        self.f1_4s[f1_4s_used].add_simulation(server)
                    f1_4s_used += 1
                else:
                    self.f1_16s[f1_16s_used].add_switch(switch)
                    for server in downlinknodes:
                        self.f1_16s[f1_16s_used].add_simulation(server)
                    f1_16s_used += 1
            else:
                assert False, "Mixed downlinks currently not supported."""



class IpAddrRunFarm(RunFarm):
    """ This manages the set of AWS resources requested for the run farm. It
    essentially decouples launching instances from assigning them to simulations.

    This way, you can assign "instances" to simulations first, and then assign
    the real instance ids to the instance objects managed here."""

    #def __init__(self, num_f1_16, num_f1_4, num_f1_2, num_m4_16, runfarmtag,
    #             run_instance_market, spot_interruption_behavior,
    #             spot_max_price):

    def __init__(self, arg_dict):
        RunFarm.__init__(self, arg_dict)

        self.fpga_node = None

    def parse_args(self):
        self.fpga_node = getattr(
            import_module("runtools.run_farm_instances"),
            self.get_arg("runinstancedescriptor"))()

        self.fpga_node.set_ip(self.get_arg("ipaddr"))

    def post_launch_binding(self, mock = False):
        return

    def launch_run_farm(self):
        return

    def terminate_run_farm(self, terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                           terminatesomem4_16, forceterminate):
        return

    def get_all_host_nodes(self):
        """ Get inst objects for all host nodes in the run farm that are bound to
        a real instance. """
        return [self.fpga_node]

    def lookup_by_ip_addr(self, ipaddr):
        """ Get an instance object from its IP address. """
        if ipaddr == self.fpga_node.get_ip():
            return self.fpga_node
        return None

    def pass_no_net_host_mapping(self, firesim_topology):
        # only if we have no networks - pack simulations
        # assumes the user has provided enough or more slots
        servers = firesim_topology.get_dfs_order_servers()
        serverind = 0

        for x in range(self.fpga_node.get_num_fpga_slots_max()):
            self.fpga_node.add_simulation(servers[serverind])
            serverind += 1
            if len(servers) == serverind:
                return

        assert serverind == len(servers), "ERR: all servers were not assigned to a host."

    def pass_simple_networked_host_node_mapping(self, firesim_topology):
        """ A very simple host mapping strategy.  """

        assert(False, "Unable to run networked topo")
