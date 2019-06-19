""" Node types necessary to construct a FireSimTopology. """

import logging

from runtools.switch_model_config import AbstractSwitchToSwitchConfig
from util.streamlogger import StreamLogger
from fabric.api import *

rootLogger = logging.getLogger()


class FireSimLink(object):
    """ This represents a link that connects different FireSimNodes.

    Terms:
        Naming assumes a tree-ish topology, with roots at the top, leaves at the
        bottom. So in a topology like:
                        RootSwitch
                        /        \
              Link A   /          \  Link B
                      /            \
                    Sim X         Sim Y

        "Uplink side" of Link A is RootSwitch.
        "Downlink side" of Link A is Sim X.
        Sim X has an uplink connected to RootSwitch.
        RootSwitch has a downlink to Sim X.

    """

    # links have a globally unique identifier, currently used for naming
    # shmem regions for Shmem Links
    next_unique_link_identifier = 0

    def __init__(self, uplink_side, downlink_side):
        self.id = FireSimLink.next_unique_link_identifier
        FireSimLink.next_unique_link_identifier += 1
        # format as 100 char hex string padded with zeroes
        self.id_as_str = format(self.id, '0100X')
        self.uplink_side = None
        self.downlink_side = None
        self.port = None
        self.set_uplink_side(uplink_side)
        self.set_downlink_side(downlink_side)

    def set_uplink_side(self, fsimnode):
        self.uplink_side = fsimnode

    def set_downlink_side(self, fsimnode):
        self.downlink_side = fsimnode

    def get_uplink_side(self):
        return self.uplink_side

    def get_downlink_side(self):
        return self.downlink_side

    def link_hostserver_port(self):
        """ Get the port used for this Link. This should only be called for
        links implemented with SocketPorts. """
        if self.port is None:
            self.port = self.get_uplink_side().host_instance.allocate_host_port()
        return self.port

    def link_hostserver_ip(self):
        """ Get the IP address used for this Link. This should only be called for
        links implemented with SocketPorts. """
        assert self.get_uplink_side().host_instance.is_bound_to_real_instance(), "Instances must be bound to private IP to emit switches with uplinks. i.e. you must have a running Run Farm."
        return self.get_uplink_side().host_instance.get_private_ip()

    def link_crosses_hosts(self):
        """ Return True if the user has mapped the two endpoints of this link to
        separate hosts. This implies a SocketServerPort / SocketClientPort will be used
        to implement the Link. If False, use a sharedmem port to implement the link. """
        if type(self.get_downlink_side()) == FireSimDummyServerNode:
            return False
        return self.get_uplink_side().host_instance != self.get_downlink_side().host_instance

    def get_global_link_id(self):
        """ Return the globally unique link id, used for naming shmem ports. """
        return self.id_as_str


class FireSimNode(object):
    """ This represents a node in the high-level FireSim Simulation Topology
    Graph. These nodes are either

    a) Actual Switches
    b) Dummy Switches
    c) Simulation Nodes

    Initially, a user just constructs a standard tree that describes the
    target. Then, they define a bunch of passes that run on the tree, for
    example:

        1) Mapping nodes to host EC2 instances
        2) Assigning MAC addresses to simulators
        3) Assigning workloads to run to simulators

    """

    def __init__(self):
        self.downlinks = []
        # used when there are multiple links between switches to disambiguate
        #self.downlinks_consumed = []
        self.uplinks = []
        self.host_instance = None

    def add_downlink(self, firesimnode):
        """ A "downlink" is a link that will take you further from the root
        of the tree. Users define a tree topology by specifying "downlinks".
        Uplinks are automatically inferred. """
        linkobj = FireSimLink(self, firesimnode)
        firesimnode.add_uplink(linkobj)
        self.downlinks.append(linkobj)
        #self.downlinks_consumed.append(False)

    def add_downlinks(self, firesimnodes):
        """ Just a convenience function to add multiple downlinks at once.
        Assumes downlinks in the supplied list are ordered. """
        [self.add_downlink(node) for node in firesimnodes]

    def add_uplink(self, firesimlink):
        """ This is only for internal use - uplinks are automatically populated
        when a node is specified as the downlink of another.

        An "uplink" is a link that takes you towards one of the roots of the
        tree."""
        self.uplinks.append(firesimlink)

    def num_links(self):
        """ Return the total number of nodes. """
        return len(self.downlinks) + len(self.uplinks)

    def run_node_simulation(self):
        """ Override this to provide the ability to launch your simulation. """
        pass

    def terminate_node_simulation(self):
        """ Override this to provide the ability to terminate your simulation. """
        pass

    def assign_host_instance(self, host_instance_run_farm_object):
        self.host_instance = host_instance_run_farm_object

    def get_host_instance(self):
        return self.host_instance


class FireSimServerNode(FireSimNode):
    """ This is a simulated server instance in FireSim. """
    SERVERS_CREATED = 0

    def __init__(self, server_hardware_config=None, server_link_latency=None,
                 server_bw_max=None, server_profile_interval=None,
                 trace_enable=None, trace_start=None, trace_end=None):
        super(FireSimServerNode, self).__init__()
        self.server_hardware_config = server_hardware_config
        self.server_link_latency = server_link_latency
        self.server_bw_max = server_bw_max
        self.server_profile_interval = server_profile_interval
        self.trace_enable = trace_enable
        self.trace_start = trace_start
        self.trace_end = trace_end
        self.job = None
        self.server_id_internal = FireSimServerNode.SERVERS_CREATED
        FireSimServerNode.SERVERS_CREATED += 1

    def set_server_hardware_config(self, server_hardware_config):
        self.server_hardware_config = server_hardware_config

    def get_server_hardware_config(self):
        return self.server_hardware_config

    def assign_mac_address(self, macaddr):
        self.mac_address = macaddr

    def get_mac_address(self):
        return self.mac_address

    def diagramstr(self):
        msg = """{}:{}\n----------\nMAC: {}\n{}\n{}""".format("FireSimServerNode",
                                                   str(self.server_id_internal),
                                                   str(self.mac_address),
                                                   str(self.job),
                                                   str(self.server_hardware_config))
        return msg

    def get_sim_start_command(self, slotno):
        """ return the command to start the simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        shmemportname = "default"
        if self.uplinks:
            shmemportname = self.uplinks[0].get_global_link_id()

        return self.server_hardware_config.get_boot_simulation_command(
            self.get_mac_address(), self.get_rootfs_name(), slotno,
            self.server_link_latency, self.server_bw_max,
            self.server_profile_interval, self.get_bootbin_name(),
            self.trace_enable, self.trace_start, self.trace_end, shmemportname)

    def copy_back_job_results_from_run(self, slotno):
        """
        1) Make the local directory for this job's output
        2) Copy back UART log
        3) Mount rootfs on the remote node and copy back files

        TODO: move this somewhere else, it's kinda in a weird place...
        """
        jobinfo = self.get_job()
        simserverindex = slotno
        job_results_dir = self.get_job().parent_workload.job_results_dir
        job_dir = """{}/{}/""".format(job_results_dir, jobinfo.jobname)
        with StreamLogger('stdout'), StreamLogger('stderr'):
            localcap = local("""mkdir -p {}""".format(job_dir), capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

        # mount rootfs, copy files from it back to local system
        if self.get_rootfs_name() is not None:
            mountpoint = """/home/centos/sim_slot_{}/mountpoint""".format(simserverindex)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""sudo mkdir -p {}""".format(mountpoint))
                run("""sudo mount /home/centos/sim_slot_{}/{} {}""".format(simserverindex, self.get_rootfs_name(), mountpoint))
                run("""sudo chmod -Rf 777 {}""".format(mountpoint))

            ## copy back files from inside the rootfs
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                for outputfile in jobinfo.outputs:
                    get(remote_path=mountpoint + outputfile, local_path=job_dir)

            ## unmount
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""sudo umount {}""".format(mountpoint))

        ## copy output files generated by the simulator that live on the host:
        ## e.g. uartlog, memory_stats.csv, etc
        remote_sim_run_dir = """/home/centos/sim_slot_{}/""".format(simserverindex)
        for simoutputfile in jobinfo.simoutputs:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                get(remote_path=remote_sim_run_dir + simoutputfile, local_path=job_dir)

    def get_sim_kill_command(self, slotno):
        """ return the command to kill the simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        return self.server_hardware_config.get_kill_simulation_command()

    def get_required_files_local_paths(self):
        """ Return local paths of all stuff needed to run this simulation as
        an array. """
        all_paths = []

        if self.get_job().rootfs_path() is not None:
            all_paths.append([self.get_job().rootfs_path(), ''])

        all_paths.append([self.get_job().bootbinary_path(), ''])

        all_paths.append([self.server_hardware_config.get_local_driver_path(), ''])
        all_paths.append([self.server_hardware_config.get_local_runtime_conf_path(), ''])
        all_paths.append([self.server_hardware_config.get_local_assert_def_path(), ''])
        return all_paths

    def get_agfi(self):
        """ Return the AGFI that should be flashed. """
        return self.server_hardware_config.agfi

    def assign_job(self, job):
        """ Assign a job to this node. """
        self.job = job

    def get_job(self):
        """ Get the job assigned to this node. """
        return self.job

    def get_job_name(self):
        return self.job.jobname

    def get_rootfs_name(self, dummyindex=0):
        if self.get_job().rootfs_path() is None:
            return None
        elif dummyindex:
            return self.get_job().rootfs_path().split("/")[-1] + "-" + str(dummyindex)
        else:
            return self.get_job().rootfs_path().split("/")[-1]

    def get_bootbin_name(self, dummyindex=0):
        if dummyindex:
            return self.get_job().bootbinary_path().split("/")[-1] + "-" + str(dummyindex)
        return self.get_job().bootbinary_path().split("/")[-1]


class FireSimSuperNodeServerNode(FireSimServerNode):
    """ This is the main server node for supernode mode. This knows how to
    call out to dummy server nodes to get all the info to launch the one
    command line to run the FPGA sim that has N > 1 sims on one fpga."""

    def supernode_get_num_siblings(self):
        siblings = 0
        count = False
        for index, servernode in enumerate(map( lambda x : x.get_downlink_side(), self.uplinks[0].get_uplink_side().downlinks)):
            if count:
                if isinstance(servernode, FireSimDummyServerNode):
                    count += 1
                else:
                    return count
            elif self == servernode:
                count = True
        return count

    def supernode_get_sibling(self, siblingindex):
        """ return the sibling for supernode mode.
        siblingindex = 1 -> next sibling, 2 = second, 3 = last one."""
        for index, servernode in enumerate(map( lambda x : x.get_downlink_side(), self.uplinks[0].get_uplink_side().downlinks)):
            if self == servernode:
                return self.uplinks[0].get_uplink_side().downlinks[index+siblingindex].get_downlink_side()

    def supernode_get_sibling_mac_address(self, siblingindex):
        """ return the sibling's mac address for supernode mode.
        siblingindex = 1 -> next sibling, 2 = second, 3 = last one."""
        return self.supernode_get_sibling(siblingindex).get_mac_address()

    def supernode_get_sibling_rootfs(self, siblingindex):
        """ return the sibling's rootfs for supernode mode.
        siblingindex = 1 -> next sibling, 2 = second, 3 = last one."""
        return self.supernode_get_sibling(siblingindex).get_rootfs_name(siblingindex)

    def supernode_get_sibling_bootbin(self, siblingindex):
        """ return the sibling's rootfs for supernode mode.
        siblingindex = 1 -> next sibling, 2 = second, 3 = last one."""
        return self.supernode_get_sibling(siblingindex).get_bootbin_name(siblingindex)

    def supernode_get_sibling_rootfs_path(self, siblingindex):
        return self.supernode_get_sibling(siblingindex).get_job().rootfs_path()

    def supernode_get_sibling_bootbinary_path(self, siblingindex):
        return self.supernode_get_sibling(siblingindex).get_job().bootbinary_path()

    def supernode_get_sibling_link_latency(self, siblingindex):
        return self.supernode_get_sibling(siblingindex).server_link_latency

    def supernode_get_sibling_bw_max(self, siblingindex):
        return self.supernode_get_sibling(siblingindex).server_bw_max

    def supernode_get_sibling_shmemportname(self, siblingindex):
        return self.supernode_get_sibling(siblingindex).uplinks[0].get_global_link_id()

    def get_sim_start_command(self, slotno):
        """ return the command to start the simulation. assumes it will be
        called in a directory where its required_files are already located.

        Currently hardcoded to 4 nodes.
        """

        num_siblings = self.supernode_get_num_siblings()

        all_macs = [self.get_mac_address()] + [self.supernode_get_sibling_mac_address(x) for x in range(1, num_siblings)]
        all_rootfses = [self.get_rootfs_name()] + [self.supernode_get_sibling_rootfs(x) for x in range(1, num_siblings)]
        all_bootbins = [self.get_bootbin_name()] + [self.supernode_get_sibling_bootbin(x) for x in range(1, num_siblings)]
        all_linklatencies = [self.server_link_latency] + [self.supernode_get_sibling_link_latency(x) for x in range(1, num_siblings)]
        all_maxbws = [self.server_bw_max] + [self.supernode_get_sibling_bw_max(x) for x in range(1, num_siblings)]

        all_shmemportnames = ["default" for x in range(num_siblings)]
        if self.uplinks:
            all_shmemportnames = [self.uplinks[0].get_global_link_id()] + [self.supernode_get_sibling_shmemportname(x) for x in range(1, num_siblings)]

        return self.server_hardware_config.get_supernode_boot_simulation_command(
            slotno, all_macs, all_rootfses, all_linklatencies, all_maxbws,
            self.server_profile_interval, all_bootbins, self.trace_enable,
            self.trace_start, self.trace_end, all_shmemportnames)

    def get_required_files_local_paths(self):
        """ Return local paths of all stuff needed to run this simulation as
        an array. """

        def get_path_trailing(filepath):
            return filepath.split("/")[-1]
        def local_and_remote(filepath, index):
            return [filepath, get_path_trailing(filepath) + str(index)]

        all_paths = []
        if self.get_job().rootfs_path() is not None:
            all_paths.append([self.get_job().rootfs_path(),
                              self.get_rootfs_name()])

        num_siblings = self.supernode_get_num_siblings()

        for x in range(1, num_siblings):
            sibling_rootfs_path = self.supernode_get_sibling_rootfs_path(x)
            if sibling_rootfs_path is not None:
                all_paths.append([sibling_rootfs_path,
                                  self.supernode_get_sibling_rootfs(x)])

        all_paths.append([self.get_job().bootbinary_path(),
                          self.get_bootbin_name()])

        for x in range(1, num_siblings):
            all_paths.append([self.supernode_get_sibling_bootbinary_path(x),
                              self.supernode_get_sibling_bootbin(x)])

        all_paths.append([self.server_hardware_config.get_local_driver_path(), ''])
        all_paths.append([self.server_hardware_config.get_local_runtime_conf_path(), ''])
        all_paths.append([self.server_hardware_config.get_local_assert_def_path(), ''])
        return all_paths

    def get_rootfs_name(self, dummyindex=0):
        if self.get_job().rootfs_path() is None:
            return None
        elif dummyindex:
            return self.get_job().rootfs_path().split("/")[-1] + "-" + str(dummyindex)
        else:
            return self.get_job().rootfs_path().split("/")[-1]

    def get_bootbin_name(self, dummyindex=0):
        if dummyindex:
            return self.get_job().bootbinary_path().split("/")[-1] + "-" + str(dummyindex)
        return self.get_job().bootbinary_path().split("/")[-1]


class FireSimDummyServerNode(FireSimServerNode):
    """ This is a dummy server node for supernode mode. """
    def __init__(self, server_hardware_config=None, server_link_latency=None,
                 server_bw_max=None):
        super(FireSimDummyServerNode, self).__init__(server_hardware_config,
                                                     server_link_latency,
                                                     server_bw_max)


class FireSimSwitchNode(FireSimNode):
    """ This is a simulated switch instance in FireSim.

    This is purposefully simple. Abstractly, switches don't do much/have
    much special configuration."""

    # used to give switches a global ID
    SWITCHES_CREATED = 0

    def __init__(self, switching_latency=None, link_latency=None, bandwidth=None):
        super(FireSimSwitchNode, self).__init__()
        self.switch_id_internal = FireSimSwitchNode.SWITCHES_CREATED
        FireSimSwitchNode.SWITCHES_CREATED += 1
        self.switch_table = None
        self.switch_link_latency = link_latency
        self.switch_switching_latency = switching_latency
        self.switch_bandwidth = bandwidth

        # switch_builder is a class designed to emit a particular switch model.
        # it should take self and then be able to emit a particular switch model's
        # binary. this is populated when build_switch_sim_binary is called
        #self.switch_builder = None
        self.switch_builder = AbstractSwitchToSwitchConfig(self)

    def build_switch_sim_binary(self):
        """ This actually emits a config and builds the switch binary that
        can be used to do the simulation. """
        self.switch_builder.buildswitch()

    def get_required_files_local_paths(self):
        """ Return local paths of all stuff needed to run this simulation as
        array. """
        all_paths = []
        all_paths.append(self.switch_builder.switch_binary_local_path())
        return all_paths

    def get_switch_start_command(self):
        return self.switch_builder.run_switch_simulation_command()

    def get_switch_kill_command(self):
        return self.switch_builder.kill_switch_simulation_command()

    def copy_back_switchlog_from_run(self, job_results_dir, switch_slot_no):
        """
        Copy back the switch log for this switch

        TODO: move this somewhere else, it's kinda in a weird place...
        """
        job_dir = """{}/switch{}/""".format(job_results_dir, self.switch_id_internal)

        with StreamLogger('stdout'), StreamLogger('stderr'):
            localcap = local("""mkdir -p {}""".format(job_dir), capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

        ## copy output files generated by the simulator that live on the host:
        ## e.g. uartlog, memory_stats.csv, etc
        remote_sim_run_dir = """/home/centos/switch_slot_{}/""".format(switch_slot_no)
        for simoutputfile in ["switchlog"]:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                get(remote_path=remote_sim_run_dir + simoutputfile, local_path=job_dir)


    def diagramstr(self):
        msg = """{}:{}\n---------\ndownlinks: {}\nswitchingtable: {}""".format(
            "FireSimSwitchNode", str(self.switch_id_internal), ", ".join(map(str, self.downlinkmacs)),
            ", ".join(map(str, self.switch_table)))
        return msg
