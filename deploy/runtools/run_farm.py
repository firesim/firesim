""" Run Farm management. """

import re
import logging

from awstools.awstools import *
from fabric.api import *
from fabric.contrib.project import rsync_project
from util.streamlogger import StreamLogger
import time
from importlib import import_module
from runtools.run_farm_instances import FPGAInst
from utils import inheritors

rootLogger = logging.getLogger()

class MockBoto3Instance:
    """ This is used for testing without actually launching instances. """

    # don't use 0 unless you want stuff copied to your own instance.
    base_ip = 1

    def __init__(self):
        self.ip_addr_int = MockBoto3Instance.base_ip
        MockBoto3Instance.base_ip += 1
        self.private_ip_address = ".".join([str((self.ip_addr_int >> (8*x)) & 0xFF) for x in [3, 2, 1, 0]])


class RunFarm(object):

    NAME = ""

    def __init__(self, args):
        """ Initialization function.

        Parameters:
            build_config (BuildConfig): Build config associated with this dispatcher
            args (list/dict): List or dict of args (i.e. options) passed to the dispatcher
        """

        self.args = args

    def parse_args(self):
        """ Parse default build host arguments. Can be overridden by child classes. """
        return

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

    NAME = "aws-ec2-f1"

    def __init__(self, args):
        RunFarm.__init__(self, args)

        self.f1_16s = []
        self.f1_4s = []
        self.f1_2s = []
        self.m4_16s = []

        self.run_farm_tag = None
        self.run_instance_market = None
        self.spot_interruption_behavior = None
        self.spot_max_price = None

    def parse_args(self):
        run_farm_tag_prefix = "" if 'FIRESIM_RUN_FARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUN_FARM_PREFIX']
        if run_farm_tag_prefix != "":
            run_farm_tag_prefix += "-"

        self.run_farm_tag = run_farm_tag_prefix + self.args['run-farm-tag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.run_farm_tag = aws_resource_names_dict['runfarmprefix'] + "-" + self.run_farm_tag

        num_f1_16 = self.args['f1_16xlarges']
        num_f1_4 = self.args['f1_4xlarges']
        num_m4_16 = self.args['m4_16xlarges']
        num_f1_2 = self.args['f1_2xlarges']

        self.run_instance_market = self.args['run-instance-market']
        self.spot_interruption_behavior = self.args['spot-interruption-behavior']
        self.spot_max_price = self.args['spot-max-price']

        self.f1_16s = [F1Inst(8) for x in range(num_f1_16)]
        self.f1_4s = [F1Inst(2) for x in range(num_f1_4)]
        self.f1_2s = [F1Inst(1) for x in range(num_f1_2)]
        self.m4_16s = [M4_16() for x in range(num_m4_16)]

        allinsts = self.f1_16s + self.f1_2s + self.f1_4s + self.m4_16s
        for node in allinsts:
            node.set_sim_dir(self.override_simulation_dir)

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
            self.run_farm_tag, 'f1.16xlarge'))
        available_f1_4_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.run_farm_tag, 'f1.4xlarge'))
        available_m4_16_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
            self.run_farm_tag, 'm4.16xlarge'))
        available_f1_2_instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(
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


    def launch_run_farm(self):
        """ Launch the run farm. """

        run_farm_tag = self.run_farm_tag
        runinstancemarket = self.run_instance_market
        spotinterruptionbehavior = self.spot_interruption_behavior
        spotmaxprice = self.spot_max_price

        num_f1_16xlarges = len(self.f1_16s)
        num_f1_4xlarges = len(self.f1_4s)
        num_f1_2xlarges = len(self.f1_2s)
        num_m4_16xlarges = len(self.m4_16s)

        # actually launch the instances
        f1_16s = launch_run_instances('f1.16xlarge', num_f1_16xlarges, run_farm_tag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice)
        f1_4s = launch_run_instances('f1.4xlarge', num_f1_4xlarges, run_farm_tag,
                                     runinstancemarket, spotinterruptionbehavior,
                                     spotmaxprice)
        m4_16s = launch_run_instances('m4.16xlarge', num_m4_16xlarges, run_farm_tag,
                                      runinstancemarket, spotinterruptionbehavior,
                                      spotmaxprice)
        f1_2s = launch_run_instances('f1.2xlarge', num_f1_2xlarges, run_farm_tag,
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
        run_farm_tag = self.run_farm_tag

        # get instances that belong to the run farm. sort them in case we're only
        # terminating some, to try to get intra-availability-zone locality
        f1_16_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(run_farm_tag, 'f1.16xlarge'))
        f1_4_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(run_farm_tag, 'f1.4xlarge'))
        m4_16_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(run_farm_tag, 'm4.16xlarge'))
        f1_2_instances = instances_sorted_by_avail_ip(
            get_instances_by_tag_type(run_farm_tag, 'f1.2xlarge'))

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



class IpAddrRunFarm(RunFarm):
    """ This manages the set of AWS resources requested for the run farm. It
    essentially decouples launching instances from assigning them to simulations.

    This way, you can assign "instances" to simulations first, and then assign
    the real instance ids to the instance objects managed here."""

    NAME = "unmanaged"

    def __init__(self, args):
        RunFarm.__init__(self, args)

<<<<<<< HEAD
        self.fpga_nodes = []
=======
        with prefix('cd ../'), \
             StreamLogger('stdout'), \
             StreamLogger('stderr'):
            # use local version of aws_fpga on runfarm nodes
            aws_fpga_upstream_version = local('git -C platforms/f1/aws-fpga describe --tags --always --dirty', capture=True)
            if "-dirty" in aws_fpga_upstream_version:
                rootLogger.critical("Unable to use local changes to aws-fpga. Continuing without them.")
        self.instance_logger("""Installing AWS FPGA SDK on remote nodes. Upstream hash: {}""".format(aws_fpga_upstream_version))
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run('git clone https://github.com/aws/aws-fpga')
            run('cd aws-fpga && git checkout ' + aws_fpga_upstream_version)
        with cd('/home/centos/aws-fpga'), StreamLogger('stdout'), StreamLogger('stderr'):
            run('source sdk_setup.sh')
>>>>>>> origin/local-fpga

    def parse_args(self):
	dispatch_dict = dict([(x.NAME, x.__name__) for x in inheritors(FPGAInst)])

        default_num_fpgas = self.args.get("default-num-fpgas")
        default_platform = self.args.get("default-platform")
        default_simulation_dir = self.args.get("default-simulation-dir")

        runhosts_list = self.args["runhosts"]

        # TODO: currently, only supports 1 ip address
        assert(len(runhosts_list) == 1)

        for runhost in runhosts_list:
            if type(runhost) is dict:
                # add element { ip-addr: { arg1: val1, arg2: val2, ... } }
                assert(len(runhost.keys()) == 1)

                ip_addr = runhost.keys()[0]
                ip_args = runhost.values()[0]

                num_fpgas = ip_args.get("override-num-fpgas", default_num_fpgas)
                platform = ip_args.get("override-platform", default_platform)
                simulation_dir = ip_args.get("override-simulation-dir", default_simulation_dir)

                fpga_node = getattr(
                    import_module("runtools.run_farm_instances"),
                    dispatch_dict[platform])(num_fpgas)

                fpga_node.set_ip(ip_addr)
                fpga_node.set_sim_dir(simulation_dir)

                self.fpga_nodes.append(fpga_node)
            elif type(runhost) is str:
                # add element w/ defaults
                fpga_node = getattr(
                    import_module("runtools.run_farm_instances"),
                    dispatch_dict[default_platform])(default_num_fpgas)

                fpga_node.set_ip(runhost)
                fpga_node.set_sim_dir(default_simulation_dir)

                self.fpga_nodes.append(fpga_node)
            else:
                raise Exception("Unknown runhost type")

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
        return self.fpga_nodes

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

        for x in range(self.fpga_node.get_num_fpga_slots_max()):
            self.fpga_node.add_simulation(servers[serverind])
            serverind += 1
            if len(servers) == serverind:
                return

<<<<<<< HEAD
        assert serverind == len(servers), "ERR: all servers were not assigned to a host."
=======
        for slotno in range(self.parentnode.get_num_fpga_slots_consumed(), self.parentnode.get_num_fpga_slots_max()):
            self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, dummyagfi))
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))


    def load_xdma(self):
        """ load the xdma kernel module. """
        # fpga mgmt tools seem to force load xocl after a flash now...
        # xocl conflicts with the xdma driver, which we actually want to use
        # so we just remove everything for good measure before loading xdma:
        self.unload_xdma()
        # now load xdma
        self.instance_logger("Loading XDMA Driver Kernel Module.")
        # TODO: can make these values automatically be chosen based on link lat
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("sudo insmod /home/centos/xdma/linux_kernel_drivers/xdma/xdma.ko poll_mode=1")

    def start_ila_server(self):
        """ start the vivado hw_server and virtual jtag on simulation instance.) """
        self.instance_logger("Starting Vivado hw_server.")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""screen -S hw_server -d -m bash -c "script -f -c 'hw_server'"; sleep 1""")
        self.instance_logger("Starting Vivado virtual JTAG.")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""screen -S virtual_jtag -d -m bash -c "script -f -c 'sudo fpga-start-virtual-jtag -P 10201 -S 0'"; sleep 1""")

    def kill_ila_server(self):
        """ Kill the vivado hw_server and virtual jtag """
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run("sudo pkill -SIGKILL hw_server")
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run("sudo pkill -SIGKILL fpga-local-cmd")

    def copy_sim_slot_infrastructure(self, slotno):
        """ copy all the simulation infrastructure to the remote node. """
        serv = self.parentnode.fpga_slots[slotno]
        if serv is None:
            # slot unassigned
            return

        self.instance_logger("""Copying FPGA simulation infrastructure for slot: {}.""".format(slotno))

        remote_sim_dir = """/home/centos/sim_slot_{}/""".format(slotno)
        remote_sim_rsync_dir = remote_sim_dir + "rsyncdir/"
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""mkdir -p {}""".format(remote_sim_rsync_dir))

        files_to_copy = serv.get_required_files_local_paths()
        for filename in files_to_copy:
            # here, filename is a pair of (local path, remote path)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                # -z --inplace
                rsync_cap = rsync_project(local_dir=filename[0], remote_dir=remote_sim_rsync_dir + '/' + filename[1],
                              ssh_opts="-o StrictHostKeyChecking=no", extra_opts="-L", capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""cp -r {}/* {}/""".format(remote_sim_rsync_dir, remote_sim_dir), shell=True)


    def copy_switch_slot_infrastructure(self, switchslot):
        self.instance_logger("""Copying switch simulation infrastructure for switch slot: {}.""".format(switchslot))

        remote_switch_dir = """/home/centos/switch_slot_{}/""".format(switchslot)
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""mkdir -p {}""".format(remote_switch_dir))

        switch = self.parentnode.switch_slots[switchslot]
        files_to_copy = switch.get_required_files_local_paths()
        for filename in files_to_copy:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                put(filename, remote_switch_dir, mirror_local_mode=True)

    def start_switch_slot(self, switchslot):
        self.instance_logger("""Starting switch simulation for switch slot: {}.""".format(switchslot))
        remote_switch_dir = """/home/centos/switch_slot_{}/""".format(switchslot)
        switch = self.parentnode.switch_slots[switchslot]
        with cd(remote_switch_dir), StreamLogger('stdout'), StreamLogger('stderr'):
            run(switch.get_switch_start_command())

    def start_sim_slot(self, slotno):
        self.instance_logger("""Starting FPGA simulation for slot: {}.""".format(slotno))
        remote_sim_dir = """/home/centos/sim_slot_{}/""".format(slotno)
        server = self.parentnode.fpga_slots[slotno]
        with cd(remote_sim_dir), StreamLogger('stdout'), StreamLogger('stderr'):
            server.run_sim_start_command(slotno)

    def kill_switch_slot(self, switchslot):
        """ kill the switch in slot switchslot. """
        self.instance_logger("""Killing switch simulation for switchslot: {}.""".format(switchslot))
        switch = self.parentnode.switch_slots[switchslot]
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run(switch.get_switch_kill_command())

    def kill_sim_slot(self, slotno):
        self.instance_logger("""Killing FPGA simulation for slot: {}.""".format(slotno))
        server = self.parentnode.fpga_slots[slotno]
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run(server.get_sim_kill_command(slotno))

    def instance_assigned_simulations(self):
        """ return true if this instance has any assigned fpga simulations. """
        if not isinstance(self.parentnode, M4_16):
            if any(self.parentnode.fpga_slots):
                return True
        return False

    def instance_assigned_switches(self):
        """ return true if this instance has any assigned switch simulations. """
        if any(self.parentnode.switch_slots):
            return True
        return False

    def infrasetup_instance(self):
        """ Handle infrastructure setup for this instance. """
        # check if fpga node
        if self.instance_assigned_simulations():
            # This is an FPGA-host node.

            # copy fpga sim infrastructure
            for slotno in range(self.parentnode.get_num_fpga_slots_consumed()):
                self.copy_sim_slot_infrastructure(slotno)

            self.get_and_install_aws_fpga_sdk()
            # unload any existing edma/xdma/xocl
            self.unload_xrt_and_xocl()
            # copy xdma driver
            self.fpga_node_xdma()
            # load xdma
            self.load_xdma()

            # setup nbd/qcow infra
            self.fpga_node_qcow()
            # load nbd module
            self.load_nbd_module()

            # clear/flash fpgas
            self.clear_fpgas()
            self.flash_fpgas()

            # re-load XDMA
            self.load_xdma()

            #restart (or start form scratch) ila server
            self.kill_ila_server()
            self.start_ila_server()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(self.parentnode.get_num_switch_slots_consumed()):
                self.copy_switch_slot_infrastructure(slotno)


    def start_switches_instance(self):
        """ Boot up all the switches in a screen. """
        # remove shared mem pages used by switches
        if self.instance_assigned_switches():
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

            for slotno in range(self.parentnode.get_num_switch_slots_consumed()):
                self.start_switch_slot(slotno)

    def start_simulations_instance(self):
        """ Boot up all the sims in a screen. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(self.parentnode.get_num_fpga_slots_consumed()):
                self.start_sim_slot(slotno)

    def kill_switches_instance(self):
        """ Kill all the switches on this instance. """
        if self.instance_assigned_switches():
            for slotno in range(self.parentnode.get_num_switch_slots_consumed()):
                self.kill_switch_slot(slotno)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

    def kill_simulations_instance(self, disconnect_all_nbds=True):
        """ Kill all simulations on this instance. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(self.parentnode.get_num_fpga_slots_consumed()):
                self.kill_sim_slot(slotno)
        if disconnect_all_nbds:
            # disconnect all NBDs
            self.disconnect_all_nbds_instance()

    def running_simulations(self):
        """ collect screen results from node to see what's running on it. """
        simdrivers = []
        switches = []
        with settings(warn_only=True), hide('everything'):
            collect = run('screen -ls')
            for line in collect.splitlines():
                if "(Detached)" in line or "(Attached)" in line:
                    line_stripped = line.strip()
                    if "fsim" in line:
                        line_stripped = re.search('fsim([0-9][0-9]*)', line_stripped).group(0)
                        line_stripped = line_stripped.replace('fsim', '')
                        simdrivers.append(line_stripped)
                    elif "switch" in line:
                        line_stripped = re.search('switch([0-9][0-9]*)', line_stripped).group(0)
                        switches.append(line_stripped)
        return {'switches': switches, 'simdrivers': simdrivers}

    def monitor_jobs_instance(self, completed_jobs, teardown, terminateoncompletion,
                              job_results_dir):
        """ Job monitoring for this instance. """
        # make a local copy of completed_jobs, so that we can update it
        completed_jobs = list(completed_jobs)

        rootLogger.debug("completed jobs " + str(completed_jobs))

        if not self.instance_assigned_simulations() and self.instance_assigned_switches():
            # this node hosts ONLY switches and not fpga sims
            #
            # just confirm that our switches are still running
            # switches will never trigger shutdown in the cycle-accurate -
            # they should run forever until torn down
            if teardown:
                # handle the case where we're just tearing down nodes that have
                # ONLY switches
                numswitchesused = self.parentnode.get_num_switch_slots_consumed()
                for counter in range(numswitchesused):
                    switchsim = self.parentnode.switch_slots[counter]
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

                if terminateoncompletion:
                    # terminate the instance since teardown is called and instance
                    # termination is enabled
                    instanceids = get_instance_ids_for_instances([self.parentnode.boto3_instance_object])
                    terminate_instances(instanceids, dryrun=False)

                # don't really care about the return val in the teardown case
                return {'switches': dict(), 'sims': dict()}

            # not teardown - just get the status of the switch sims
            switchescompleteddict = {k: False for k in self.running_simulations()['switches']}
            for switchsim in self.parentnode.switch_slots[:self.parentnode.get_num_switch_slots_consumed()]:
                swname = switchsim.switch_builder.switch_binary_name()
                if swname not in switchescompleteddict.keys():
                    switchescompleteddict[swname] = True
            return {'switches': switchescompleteddict, 'sims': dict()}

        if self.instance_assigned_simulations():
            # this node has fpga sims attached

            # first, figure out which jobs belong to this instance.
            # if they are all completed already. RETURN, DON'T TRY TO DO ANYTHING
            # ON THE INSTNACE.
            parentslots = self.parentnode.fpga_slots
            rootLogger.debug("parentslots " + str(parentslots))
            num_parentslots_used = self.parentnode.fpga_slots_consumed
            jobnames = [slot.get_job_name() for slot in parentslots[0:num_parentslots_used]]
            rootLogger.debug("jobnames " + str(jobnames))
            already_done = all([job in completed_jobs for job in jobnames])
            rootLogger.debug("already done? " + str(already_done))
            if already_done:
                # in this case, all of the nodes jobs have already completed. do nothing.
                # this can never happen in the cycle-accurate case at a point where we care
                # about switch status, so don't bother to populate it
                jobnames_to_completed = {jname: True for jname in jobnames}
                return {'sims': jobnames_to_completed, 'switches': dict()}

            # at this point, all jobs are NOT completed. so, see how they're doing now:
            instance_screen_status = self.running_simulations()
            switchescompleteddict = {k: False for k in instance_screen_status['switches']}

            if self.instance_assigned_switches():
                # fill in whether switches have terminated for some reason
                for switchsim in self.parentnode.switch_slots[:self.parentnode.get_num_switch_slots_consumed()]:
                    swname = switchsim.switch_builder.switch_binary_name()
                    if swname not in switchescompleteddict.keys():
                        switchescompleteddict[swname] = True

            slotsrunning = [x for x in instance_screen_status['simdrivers']]

            rootLogger.debug("slots running")
            rootLogger.debug(slotsrunning)
            for slotno, jobname in enumerate(jobnames):
                if str(slotno) not in slotsrunning and jobname not in completed_jobs:
                    self.instance_logger("Slot " + str(slotno) + " completed! copying results.")
                    # NOW, we must copy off the results of this sim, since it just exited
                    parentslots[slotno].copy_back_job_results_from_run(slotno)
                    # add our job to our copy of completed_jobs, so that next,
                    # we can test again to see if this instance is "done" and
                    # can be terminated
                    completed_jobs.append(jobname)

            # determine if we're done now.
            jobs_done_q = {job: job in completed_jobs for job in jobnames}
            now_done = all(jobs_done_q.values())
            rootLogger.debug("now done: " + str(now_done))
            if now_done and self.instance_assigned_switches():
                # we're done AND we have switches running here, so kill them,
                # then copy off their logs. this handles the case where you
                # have a node with one simulation and some switches, to make
                # sure the switch logs are copied off.
                #
                # the other cases are when you have multiple sims and a cycle-acc network,
                # in which case the all() will never actually happen (unless someone builds
                # a workload where two sims exit at exactly the same time, which we should
                # advise users not to do)
                #
                # a last use case is when there's no network, in which case
                # instance_assigned_switches won't be true, so this won't be called

                self.kill_switches_instance()

                for counter, switchsim in enumerate(self.parentnode.switch_slots[:self.parentnode.get_num_switch_slots_consumed()]):
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

            if now_done and terminateoncompletion:
                # terminate the instance since everything is done and instance
                # termination is enabled
                instanceids = get_instance_ids_for_instances([self.parentnode.boto3_instance_object])
                terminate_instances(instanceids, dryrun=False)

            return {'switches': switchescompleteddict, 'sims': jobs_done_q}
>>>>>>> origin/local-fpga

    def pass_simple_networked_host_node_mapping(self, firesim_topology):
        """ A very simple host mapping strategy.  """

        assert(False, "Unable to run networked topo")
