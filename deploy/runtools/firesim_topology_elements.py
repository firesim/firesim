""" Node types necessary to construct a FireSimTopology. """

import logging
import abc
from fabric.contrib.project import rsync_project # type: ignore
from fabric.api import run, local, warn_only, get # type: ignore

from runtools.switch_model_config import AbstractSwitchToSwitchConfig
from runtools.utils import get_local_shared_libraries
from util.streamlogger import StreamLogger
from runtools.workload import WorkloadConfig, JobConfig
from runtools.run_farm import EC2Inst
from runtools.runtime_config import RuntimeHWConfig
from runtools.utils import MacAddress

from typing import Optional, List, Tuple, Sequence, Union

rootLogger = logging.getLogger()

class FireSimLink:
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
    next_unique_link_identifier: int = 0
    id: int
    id_as_str: str
    uplink_side: Optional[FireSimNode]
    downlink_side: Optional[FireSimNode]
    port: Optional[int]

    def __init__(self, uplink_side: FireSimNode, downlink_side: FireSimNode) -> None:
        self.id = FireSimLink.next_unique_link_identifier
        FireSimLink.next_unique_link_identifier += 1
        # format as 100 char hex string padded with zeroes
        self.id_as_str = format(self.id, '0100X')
        self.uplink_side = None
        self.downlink_side = None
        self.port = None
        self.set_uplink_side(uplink_side)
        self.set_downlink_side(downlink_side)

    def set_uplink_side(self, fsimnode: FireSimNode) -> None:
        self.uplink_side = fsimnode

    def set_downlink_side(self, fsimnode: FireSimNode) -> None:
        self.downlink_side = fsimnode

    def get_uplink_side(self) -> FireSimNode:
        assert self.uplink_side is not None
        return self.uplink_side

    def get_downlink_side(self) -> FireSimNode:
        assert self.downlink_side is not None
        return self.downlink_side

    def link_hostserver_port(self) -> int:
        """ Get the port used for this Link. This should only be called for
        links implemented with SocketPorts. """
        if self.port is None:
            uplink_side = self.get_uplink_side()
            self.port = uplink_side.get_host_instance().allocate_host_port()
        return self.port

    def link_hostserver_ip(self) -> str:
        """ Get the IP address used for this Link. This should only be called for
        links implemented with SocketPorts. """
        uplink_side = self.get_uplink_side()
        return uplink_side.get_host_instance().get_private_ip()

    def link_crosses_hosts(self) -> bool:
        """ Return True if the user has mapped the two endpoints of this link to
        separate hosts. This implies a SocketServerPort / SocketClientPort will be used
        to implement the Link. If False, use a sharedmem port to implement the link. """
        if type(self.get_downlink_side()) == FireSimDummyServerNode:
            return False
        uplink_side = self.get_uplink_side()
        downlink_side = self.get_downlink_side()
        return uplink_side.get_host_instance() != downlink_side.get_host_instance()

    def get_global_link_id(self) -> str:
        """ Return the globally unique link id, used for naming shmem ports. """
        return self.id_as_str


class FireSimNode(metaclass=abc.ABCMeta):
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
    downlinks: List[FireSimLink]
    downlinkmacs: List[MacAddress]
    uplinks: List[FireSimLink]
    host_instance: Optional[EC2Inst]

    def __init__(self) -> None:
        self.downlinks = []
        self.downlinkmacs = []
        # used when there are multiple links between switches to disambiguate
        #self.downlinks_consumed = []
        self.uplinks = []
        self.host_instance = None

    def add_downlink(self, firesimnode: FireSimNode) -> None:
        """ A "downlink" is a link that will take you further from the root
        of the tree. Users define a tree topology by specifying "downlinks".
        Uplinks are automatically inferred. """
        linkobj = FireSimLink(self, firesimnode)
        firesimnode.add_uplink(linkobj)
        self.downlinks.append(linkobj)
        #self.downlinks_consumed.append(False)

    def add_downlinks(self, firesimnodes: Sequence[FireSimNode]) -> None:
        """ Just a convenience function to add multiple downlinks at once.
        Assumes downlinks in the supplied list are ordered. """
        for node in firesimnodes:
            self.add_downlink(node)

    def add_uplink(self, firesimlink: FireSimLink) -> None:
        """ This is only for internal use - uplinks are automatically populated
        when a node is specified as the downlink of another.

        An "uplink" is a link that takes you towards one of the roots of the
        tree."""
        self.uplinks.append(firesimlink)

    def num_links(self) -> int:
        """ Return the total number of nodes. """
        return len(self.downlinks) + len(self.uplinks)

    def has_assigned_host_instance(self) -> bool:
        return self.host_instance is not None

    def assign_host_instance(self, host_instance_run_farm_object: EC2Inst) -> None:
        self.host_instance = host_instance_run_farm_object

    def get_host_instance(self) -> EC2Inst:
        assert self.host_instance is not None
        return self.host_instance

    @abc.abstractmethod
    def diagramstr(self) -> str:
        raise NotImplementedError


class FireSimServerNode(FireSimNode):
    """ This is a simulated server instance in FireSim. """
    SERVERS_CREATED: int = 0
    server_hardware_config: Optional[Union[RuntimeHWConfig, str]]
    server_link_latency: Optional[int]
    server_bw_max: Optional[int]
    server_profile_interval: Optional[int]
    trace_enable: Optional[bool]
    trace_select: Optional[str]
    trace_start: Optional[str]
    trace_end: Optional[str]
    trace_output_format: Optional[str]
    autocounter_readrate: Optional[int]
    zerooutdram: Optional[bool]
    disable_asserts: Optional[bool]
    print_start: Optional[str]
    print_end: Optional[str]
    print_cycle_prefix: Optional[bool]
    job: Optional[JobConfig]
    server_id_internal: int
    mac_address: Optional[MacAddress]

    def __init__(self, server_hardware_config: Optional[Union[RuntimeHWConfig, str]] = None, server_link_latency: Optional[int] = None,
                 server_bw_max: Optional[int] = None, server_profile_interval: Optional[int] = None,
                 trace_enable: Optional[bool] = None, trace_select: Optional[str] = None, trace_start: Optional[str] = None, trace_end: Optional[str] = None, trace_output_format: Optional[str] = None, autocounter_readrate: Optional[int] = None,
                 zerooutdram: Optional[bool] = None, disable_asserts: Optional[bool] = None,
                 print_start: Optional[str] = None, print_end: Optional[str] = None, print_cycle_prefix: Optional[bool] = None):
        super().__init__()
        self.server_hardware_config = server_hardware_config
        self.server_link_latency = server_link_latency
        self.server_bw_max = server_bw_max
        self.server_profile_interval = server_profile_interval
        self.trace_enable = trace_enable
        self.trace_select = trace_select
        self.trace_start = trace_start
        self.trace_end = trace_end
        self.trace_output_format = trace_output_format
        self.autocounter_readrate = autocounter_readrate
        self.zerooutdram = zerooutdram
        self.disable_asserts = disable_asserts
        self.print_start = print_start
        self.print_end = print_end
        self.print_cycle_prefix = print_cycle_prefix
        self.job = None
        self.server_id_internal = FireSimServerNode.SERVERS_CREATED
        self.mac_address = None
        FireSimServerNode.SERVERS_CREATED += 1

    def set_server_hardware_config(self, server_hardware_config: RuntimeHWConfig) -> None:
        self.server_hardware_config = server_hardware_config

    def get_server_hardware_config(self) -> Optional[Union[RuntimeHWConfig, str]]:
        return self.server_hardware_config

    def assign_mac_address(self, macaddr: MacAddress) -> None:
        self.mac_address = macaddr

    def get_mac_address(self) -> MacAddress:
        assert self.mac_address is not None
        return self.mac_address

    def process_qcow2_rootfses(self, rootfses_list: List[Optional[str]]) -> List[Optional[str]]:
        """ Take in list of all rootfses on this node. For the qcow2 ones, find
        the allocated devices, attach the device to the qcow2 image on the
        remote node, and replace it in the list with that nbd device. Return
        the new list.

        Assumes it will be called from a sim_slot_* directory."""

        assert self.has_assigned_host_instance(), "qcow2 attach cannot be done without a host instance."

        result_list = []
        for rootfsname in rootfses_list:
            if rootfsname is not None and rootfsname.endswith(".qcow2"):
                host_inst = self.get_host_instance()
                assert isinstance(host_inst, EC2Inst)
                allocd_device = host_inst.nbd_tracker.get_nbd_for_imagename(rootfsname)

                # connect the /dev/nbdX device to the rootfs
                run("""sudo qemu-nbd -c {devname} {rootfs}""".format(devname=allocd_device, rootfs=rootfsname))
                rootfsname = allocd_device
            result_list.append(rootfsname)
        return result_list

    def allocate_nbds(self) -> None:
        """ called by the allocate nbds pass to assign an nbd to a qcow2 image.
        """
        rootfses_list = [self.get_rootfs_name()]
        for rootfsname in rootfses_list:
            if rootfsname and rootfsname.endswith(".qcow2"):
                host_inst = self.get_host_instance()
                assert isinstance(host_inst, EC2Inst)
                allocd_device = host_inst.nbd_tracker.get_nbd_for_imagename(rootfsname)


    def diagramstr(self) -> str:
        msg = """{}:{}\n----------\nMAC: {}\n{}\n{}""".format("FireSimServerNode",
                                                   str(self.server_id_internal),
                                                   str(self.mac_address),
                                                   str(self.job),
                                                   str(self.server_hardware_config))
        return msg

    def run_sim_start_command(self, slotno: int) -> None:
        """ get/run the command to run a simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        shmemportname = "default"
        if self.uplinks:
            shmemportname = self.uplinks[0].get_global_link_id()

        all_macs = [self.get_mac_address()]
        all_rootfses = self.process_qcow2_rootfses([self.get_rootfs_name()])
        all_linklatencies = [self.server_link_latency]
        all_maxbws = [self.server_bw_max]
        all_bootbins = [self.get_bootbin_name()]
        all_shmemportnames = [shmemportname]

        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)
        assert (self.server_profile_interval is not None and all_bootbins is not None and self.trace_enable is not None and
            self.trace_select is not None and self.trace_start is not None and self.trace_end is not None and self.trace_output_format is not None and
            self.autocounter_readrate is not None and all_shmemportnames is not None and self.zerooutdram is not None and self.disable_asserts is not None and
            self.print_start is not None and self.print_end is not None and self.print_cycle_prefix is not None)

        runcommand = self.server_hardware_config.get_boot_simulation_command(
            slotno, all_macs, all_rootfses, all_linklatencies, all_maxbws,
            self.server_profile_interval, all_bootbins, self.trace_enable,
            self.trace_select, self.trace_start, self.trace_end, self.trace_output_format,
            self.autocounter_readrate, all_shmemportnames, self.zerooutdram, self.disable_asserts,
            self.print_start, self.print_end, self.print_cycle_prefix)

        run(runcommand)

    def copy_back_job_results_from_run(self, slotno: int) -> None:
        """
        1) Make the local directory for this job's output
        2) Copy back UART log
        3) Mount rootfs on the remote node and copy back files

        TODO: move this somewhere else, it's kinda in a weird place...
        """
        assert self.has_assigned_host_instance(), "copy requires assigned host instance"

        jobinfo = self.get_job()
        simserverindex = slotno
        job_results_dir = self.get_job().parent_workload.job_results_dir
        job_dir = """{}/{}/""".format(job_results_dir, jobinfo.jobname)
        with StreamLogger('stdout'), StreamLogger('stderr'):
            localcap = local("""mkdir -p {}""".format(job_dir), capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

            # add hw config summary per job
            localcap = local("""echo "{}" > {}/HW_CFG_SUMMARY""".format(str(self.server_hardware_config), job_dir), capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

        # mount rootfs, copy files from it back to local system
        rfsname = self.get_rootfs_name()
        if rfsname is not None:
            is_qcow2 = rfsname.endswith(".qcow2")
            mountpoint = """/home/centos/sim_slot_{}/mountpoint""".format(simserverindex)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""sudo mkdir -p {}""".format(mountpoint))

                if is_qcow2:
                    rfsname = self.get_host_instance().nbd_tracker.get_nbd_for_imagename(rfsname)
                else:
                    rfsname = """/home/centos/sim_slot_{}/{}""".format(simserverindex, rfsname)

                run("""sudo mount {blockfile} {mntpt}""".format(blockfile=rfsname, mntpt=mountpoint))
                with warn_only():
                    # ignore if this errors. not all rootfses have /etc/sysconfig/nfs
                    run("""sudo chattr -i {}/etc/sysconfig/nfs""".format(mountpoint))
                run("""sudo chown -R centos {}""".format(mountpoint))

            ## copy back files from inside the rootfs
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                for outputfile in jobinfo.outputs:
                    rsync_cap = rsync_project(remote_dir=mountpoint + outputfile,
                            local_dir=job_dir,
                            ssh_opts="-o StrictHostKeyChecking=no",
                            extra_opts="-L",
                            upload=False,
                            capture=True)
                    rootLogger.debug(rsync_cap)
                    rootLogger.debug(rsync_cap.stderr)

            ## unmount
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""sudo umount {}""".format(mountpoint))

            ## if qcow2, detach .qcow2 image from the device, we're done with it
            if is_qcow2:
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""sudo qemu-nbd -d {devname}""".format(devname=rfsname))


        ## copy output files generated by the simulator that live on the host:
        ## e.g. uartlog, memory_stats.csv, etc
        remote_sim_run_dir = """/home/centos/sim_slot_{}/""".format(simserverindex)
        for simoutputfile in jobinfo.simoutputs:
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                rsync_cap = rsync_project(remote_dir=remote_sim_run_dir + simoutputfile,
                        local_dir=job_dir,
                        ssh_opts="-o StrictHostKeyChecking=no",
                        extra_opts="-L",
                        upload=False,
                        capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

    def get_sim_kill_command(self, slotno: int) -> str:
        """ return the command to kill the simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)
        return self.server_hardware_config.get_kill_simulation_command()

    def get_required_files_local_paths(self) -> List[Tuple[str, str]]:
        """ Return local paths of all stuff needed to run this simulation as
        an array. """
        all_paths = []

        job_rootfs_path = self.get_job().rootfs_path()
        if job_rootfs_path is not None:
            self_rootfs_name = self.get_rootfs_name()
            assert self_rootfs_name is not None
            all_paths.append((job_rootfs_path, self_rootfs_name))

        all_paths.append((self.get_job().bootbinary_path(), self.get_bootbin_name()))

        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)

        driver_path = self.server_hardware_config.get_local_driver_path()
        all_paths.append((driver_path, ''))
        all_paths.append((self.server_hardware_config.get_local_runtime_conf_path(), ''))

        # shared libraries
        all_paths += get_local_shared_libraries(driver_path)

        all_paths += self.get_job().get_siminputs()
        return all_paths

    def get_agfi(self) -> str:
        """ Return the AGFI that should be flashed. """
        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)
        return self.server_hardware_config.agfi

    def assign_job(self, job: JobConfig) -> None:
        """ Assign a job to this node. """
        self.job = job

    def get_job(self) -> JobConfig:
        """ Get the job assigned to this node. """
        assert self.job is not None
        return self.job

    def get_job_name(self) -> str:
        assert self.job is not None
        return self.job.jobname

    def get_rootfs_name(self) -> Optional[str]:
        rootfs_path = self.get_job().rootfs_path()
        if rootfs_path is None:
            return None
        else:
            # prefix rootfs name with the job name to disambiguate in supernode
            # cases
            return self.get_job_name() + "-" + rootfs_path.split("/")[-1]

    def get_bootbin_name(self) -> str:
        # prefix bootbin name with the job name to disambiguate in supernode
        # cases
        return self.get_job_name() + "-" + self.get_job().bootbinary_path().split("/")[-1]


class FireSimSuperNodeServerNode(FireSimServerNode):
    """ This is the main server node for supernode mode. This knows how to
    call out to dummy server nodes to get all the info to launch the one
    command line to run the FPGA sim that has N > 1 sims on one fpga."""

    def copy_back_job_results_from_run(self, slotno: int) -> None:
        """ This override is to call copy back job results for all the dummy nodes too. """
        # first call the original
        super().copy_back_job_results_from_run(slotno)

        # call on all siblings
        num_siblings = self.supernode_get_num_siblings_plus_one()

        # TODO: for now, just hackishly give the siblings a host node.
        # fixing this properly is going to probably require a larger revamp
        # of supernode handling
        super_server_host = self.get_host_instance()
        for sibindex in range(1, num_siblings):
            sib = self.supernode_get_sibling(sibindex)
            sib.assign_host_instance(super_server_host)
            sib.copy_back_job_results_from_run(slotno)


    def allocate_nbds(self) -> None:
        """ called by the allocate nbds pass to assign an nbd to a qcow2 image.
        """
        num_siblings = self.supernode_get_num_siblings_plus_one()

        assert self.get_rootfs_name() is not None

        rootfses_list = [self.get_rootfs_name()]
        for x in range(1, num_siblings):
            sibling = self.supernode_get_sibling(x)

            rootfses_list.append(sibling.get_rootfs_name())

        for rootfsname in rootfses_list:
            assert rootfsname is not None
            if rootfsname.endswith(".qcow2"):
                allocd_device = self.get_host_instance().nbd_tracker.get_nbd_for_imagename(rootfsname)



    def supernode_get_num_siblings_plus_one(self) -> int:
        """ This returns the number of siblings the supernodeservernode has,
        plus one (because in most places, we use siblings + 1, not just siblings)
        """
        siblings = 1
        count = False
        uplink_side = self.uplinks[0].get_uplink_side()
        for index, servernode in enumerate(map(lambda x : x.get_downlink_side(), uplink_side.downlinks)):
            if count:
                if isinstance(servernode, FireSimDummyServerNode):
                    siblings += 1
                else:
                    return siblings
            elif self == servernode:
                count = True
        return siblings

    def supernode_get_sibling(self, siblingindex: int) -> FireSimDummyServerNode:
        """ return the sibling for supernode mode.
        siblingindex = 1 -> next sibling, 2 = second, 3 = last one."""
        uplink_side = self.uplinks[0].get_uplink_side()
        for index, servernode in enumerate(map(lambda x : x.get_downlink_side(), uplink_side.downlinks)):
            if self == servernode:
                node = uplink_side.downlinks[index+siblingindex].get_downlink_side()
                assert isinstance(node, FireSimDummyServerNode)
                return node
        assert False, "Should return supernode sibling"

    def run_sim_start_command(self, slotno: int) -> None:
        """ get/run the command to run a simulation. assumes it will be
        called in a directory where its required_files are already located."""

        num_siblings = self.supernode_get_num_siblings_plus_one()

        all_macs = [self.get_mac_address()]
        all_rootfses = [self.get_rootfs_name()]
        all_bootbins = [self.get_bootbin_name()]
        all_linklatencies = [self.server_link_latency]
        all_maxbws = [self.server_bw_max]

        for x in range(1, num_siblings):
            sibling = self.supernode_get_sibling(x)

            all_macs.append(sibling.get_mac_address())
            all_rootfses.append(sibling.get_rootfs_name())
            all_bootbins.append(sibling.get_bootbin_name())
            all_linklatencies.append(sibling.server_link_latency)
            all_maxbws.append(sibling.server_bw_max)

        all_rootfses = self.process_qcow2_rootfses(all_rootfses)

        all_shmemportnames = ["default" for x in range(num_siblings)]
        if self.uplinks:
            all_shmemportnames = [self.uplinks[0].get_global_link_id()]
            for x in range(1, num_siblings):
                sibling = self.supernode_get_sibling(x)

                all_shmemportnames.append(sibling.uplinks[0].get_global_link_id())

        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)
        assert (self.server_profile_interval is not None and all_bootbins is not None and self.trace_enable is not None and
            self.trace_select is not None and self.trace_start is not None and self.trace_end is not None and self.trace_output_format is not None and
            self.autocounter_readrate is not None and all_shmemportnames is not None and self.zerooutdram is not None and self.disable_asserts is not None and
            self.print_start is not None and self.print_end is not None and self.print_cycle_prefix is not None)

        runcommand = self.server_hardware_config.get_boot_simulation_command(
            slotno, all_macs, all_rootfses, all_linklatencies, all_maxbws,
            self.server_profile_interval, all_bootbins, self.trace_enable,
            self.trace_select, self.trace_start, self.trace_end, self.trace_output_format,
            self.autocounter_readrate, all_shmemportnames, self.zerooutdram, self.disable_asserts,
            self.print_start, self.print_end, self.print_cycle_prefix)

        run(runcommand)

    def get_required_files_local_paths(self) -> List[Tuple[str, str]]:
        """ Return local paths of all stuff needed to run this simulation as
        an array. """

        def get_path_trailing(filepath):
            return filepath.split("/")[-1]
        def local_and_remote(filepath, index):
            return [filepath, get_path_trailing(filepath) + str(index)]

        assert self.server_hardware_config is not None and isinstance(self.server_hardware_config, RuntimeHWConfig)

        all_paths = []
        job_rootfs_path = self.get_job().rootfs_path()
        if job_rootfs_path is not None:
            self_rootfs_name = self.get_rootfs_name()
            assert self_rootfs_name is not None
            all_paths.append((job_rootfs_path, self_rootfs_name))

        driver_path = self.server_hardware_config.get_local_driver_path()
        all_paths.append((driver_path, ''))

        # shared libraries
        all_paths += get_local_shared_libraries(driver_path)

        num_siblings = self.supernode_get_num_siblings_plus_one()

        for x in range(1, num_siblings):
            sibling = self.supernode_get_sibling(x)

            sibling_job_rootfs_path = self.get_job().rootfs_path()
            if sibling_job_rootfs_path is not None:
                sibling_rootfs_name = sibling.get_rootfs_name()
                assert sibling_rootfs_name is not None
                all_paths.append((sibling_job_rootfs_path, sibling_rootfs_name))

            all_paths.append((sibling.get_job().bootbinary_path(),
                              sibling.get_bootbin_name()))

        all_paths.append((self.get_job().bootbinary_path(),
                          self.get_bootbin_name()))

        all_paths.append((self.server_hardware_config.get_local_runtime_conf_path(), ''))
        return all_paths

class FireSimDummyServerNode(FireSimServerNode):
    """ This is a dummy server node for supernode mode. """
    def __init__(self, server_hardware_config: Optional[RuntimeHWConfig] = None, server_link_latency: Optional[int] = None,
            server_bw_max: Optional[int] = None):
        super().__init__(server_hardware_config, server_link_latency, server_bw_max)

    def allocate_nbds(self) -> None:
        """ this is handled by the non-dummy node. override so it does nothing
        when called"""
        pass


class FireSimSwitchNode(FireSimNode):
    """ This is a simulated switch instance in FireSim.

    This is purposefully simple. Abstractly, switches don't do much/have
    much special configuration."""

    # used to give switches a global ID
    SWITCHES_CREATED: int = 0
    switch_id_internal: int
    switch_table: List[int]
    switch_link_latency: Optional[int]
    switch_switching_latency: Optional[int]
    switch_bandwidth: Optional[int]
    switch_builder: AbstractSwitchToSwitchConfig

    def __init__(self, switching_latency: Optional[int] = None, link_latency: Optional[int] = None, bandwidth: Optional[int] = None):
        super().__init__()
        self.switch_id_internal = FireSimSwitchNode.SWITCHES_CREATED
        FireSimSwitchNode.SWITCHES_CREATED += 1
        self.switch_table = []
        self.switch_link_latency = link_latency
        self.switch_switching_latency = switching_latency
        self.switch_bandwidth = bandwidth

        # switch_builder is a class designed to emit a particular switch model.
        # it should take self and then be able to emit a particular switch model's
        # binary. this is populated when build_switch_sim_binary is called
        #self.switch_builder = None
        self.switch_builder = AbstractSwitchToSwitchConfig(self)

    def build_switch_sim_binary(self) -> None:
        """ This actually emits a config and builds the switch binary that
        can be used to do the simulation. """
        self.switch_builder.buildswitch()

    def get_required_files_local_paths(self) -> List[Tuple[str, str]]:
        """ Return local paths of all stuff needed to run this simulation as
        array. """
        all_paths = []
        bin = self.switch_builder.switch_binary_local_path()
        all_paths.append((bin, ''))
        all_paths += get_local_shared_libraries(bin)
        return all_paths

    def get_switch_start_command(self) -> str:
        return self.switch_builder.run_switch_simulation_command()

    def get_switch_kill_command(self) -> str:
        return self.switch_builder.kill_switch_simulation_command()

    def copy_back_switchlog_from_run(self, job_results_dir: str, switch_slot_no: int) -> None:
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


    def diagramstr(self) -> str:
        msg =  f"FireSimSwitchNode: {self.switch_id_internal}\n"
        msg += f"---------\n"
        msg += f"""downlinks: {", ".join(map(str, self.downlinkmacs))}\n"""
        msg += f"""switchingtable: {", ".join(map(str, self.switch_table))}"""
        return msg
