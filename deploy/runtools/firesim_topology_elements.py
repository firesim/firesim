""" Node types necessary to construct a FireSimTopology. """

from __future__ import annotations

import logging
import abc
from fabric.contrib.project import rsync_project # type: ignore
from fabric.api import run, local, warn_only, get, put, cd, hide # type: ignore
from fabric.exceptions import CommandTimeout # type: ignore

from runtools.switch_model_config import AbstractSwitchToSwitchConfig
from runtools.utils import get_local_shared_libraries
from runtools.simulation_data_classes import TracerVConfig, AutoCounterConfig, HostDebugConfig, SynthPrintConfig

from typing import Optional, List, Tuple, Sequence, Union, TYPE_CHECKING
if TYPE_CHECKING:
    from runtools.workload import JobConfig
    from runtools.run_farm import Inst
    from runtools.runtime_config import RuntimeHWConfig
    from runtools.utils import MacAddress
    from runtools.run_farm_deploy_managers import EC2InstanceDeployManager

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
            self.port = self.get_uplink_side().get_host_instance().allocate_host_port()
        return self.port

    def link_hostserver_host(self) -> str:
        """ Get the host used for this Link. This should only be called for
        links implemented with SocketPorts. """
        return self.get_uplink_side().get_host_instance().get_host()

    def link_crosses_hosts(self) -> bool:
        """ Return True if the user has mapped the two endpoints of this link to
        separate hosts. This implies a SocketServerPort / SocketClientPort will be used
        to implement the Link. If False, use a sharedmem port to implement the link. """
        if isinstance(self.get_downlink_side(), FireSimDummyServerNode):
            return False
        return self.get_uplink_side().get_host_instance() != self.get_downlink_side().get_host_instance()

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
    host_instance: Optional[Inst]

    def __init__(self) -> None:
        self.downlinks = []
        self.downlinkmacs = []
        self.uplinks = []
        self.host_instance = None

    def add_downlink(self, firesimnode: FireSimNode) -> None:
        """ A "downlink" is a link that will take you further from the root
        of the tree. Users define a tree topology by specifying "downlinks".
        Uplinks are automatically inferred. """
        linkobj = FireSimLink(self, firesimnode)
        firesimnode.add_uplink(linkobj)
        self.downlinks.append(linkobj)

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

    def assign_host_instance(self, host_instance_run_farm_object: Inst) -> None:
        self.host_instance = host_instance_run_farm_object

    def get_host_instance(self) -> Inst:
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
    tracerv_config: Optional[TracerVConfig]
    autocounter_config: Optional[AutoCounterConfig]
    hostdebug_config: Optional[HostDebugConfig]
    synthprint_config: Optional[SynthPrintConfig]
    job: Optional[JobConfig]
    server_id_internal: int
    mac_address: Optional[MacAddress]
    plusarg_passthrough: Optional[str]

    def __init__(self,
            server_hardware_config: Optional[Union[RuntimeHWConfig, str]] = None,
            server_link_latency: Optional[int] = None,
            server_bw_max: Optional[int] = None,
            server_profile_interval: Optional[int] = None,
            tracerv_config: Optional[TracerVConfig] = None,
            autocounter_config: Optional[AutoCounterConfig] = None,
            hostdebug_config: Optional[HostDebugConfig] = None,
            synthprint_config: Optional[SynthPrintConfig] = None,
            plusarg_passthrough: Optional[str] = None):
        super().__init__()
        self.server_hardware_config = server_hardware_config
        self.server_link_latency = server_link_latency
        self.server_bw_max = server_bw_max
        self.server_profile_interval = server_profile_interval
        self.tracerv_config = tracerv_config
        self.autocounter_config = autocounter_config
        self.hostdebug_config = hostdebug_config
        self.synthprint_config = synthprint_config
        self.job = None
        self.server_id_internal = FireSimServerNode.SERVERS_CREATED
        self.mac_address = None
        self.plusarg_passthrough = plusarg_passthrough
        FireSimServerNode.SERVERS_CREATED += 1

    def set_server_hardware_config(self, server_hardware_config: RuntimeHWConfig) -> None:
        self.server_hardware_config = server_hardware_config

    def get_server_hardware_config(self) -> Optional[Union[RuntimeHWConfig, str]]:
        return self.server_hardware_config

    def get_resolved_server_hardware_config(self) -> RuntimeHWConfig:
        assert self.server_hardware_config is not None and not isinstance(self.server_hardware_config, str)
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
                assert isinstance(host_inst.instance_deploy_manager, EC2InstanceDeployManager)
                nbd_tracker = host_inst.instance_deploy_manager.nbd_tracker
                assert nbd_tracker is not None
                allocd_device = nbd_tracker.get_nbd_for_imagename(rootfsname)

                # connect the /dev/nbdX device to the rootfs
                run("""sudo qemu-nbd -c {devname} {rootfs}""".format(devname=allocd_device, rootfs=rootfsname))
                rootfsname = allocd_device
            result_list.append(rootfsname)
        return result_list

    def allocate_nbds(self) -> None:
        """ called by the allocate nbds pass to assign an nbd to a qcow2 image. """
        rootfses_list = self.get_all_rootfs_names()
        for rootfsname in rootfses_list:
            if rootfsname is not None and rootfsname.endswith(".qcow2"):
                host_inst = self.get_host_instance()
                assert isinstance(host_inst.instance_deploy_manager, EC2InstanceDeployManager)
                nbd_tracker = host_inst.instance_deploy_manager.nbd_tracker
                assert nbd_tracker is not None
                allocd_device = nbd_tracker.get_nbd_for_imagename(rootfsname)

    def diagramstr(self) -> str:
        msg = """{}:{}\n----------\nMAC: {}\n{}\n{}""".format("FireSimServerNode",
                                                   str(self.server_id_internal),
                                                   str(self.mac_address),
                                                   str(self.job),
                                                   str(self.server_hardware_config))
        return msg

    def get_sim_start_command(self, slotno: int, sudo: bool) -> str:
        """ get the command to run a simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        shmemportname = "default"
        if self.uplinks:
            shmemportname = self.uplinks[0].get_global_link_id()

        assert self.server_link_latency is not None
        assert self.server_bw_max is not None
        assert self.server_profile_interval is not None
        assert self.tracerv_config is not None
        assert self.autocounter_config is not None
        assert self.hostdebug_config is not None
        assert self.synthprint_config is not None
        assert self.plusarg_passthrough is not None

        all_macs = [self.get_mac_address()]
        all_rootfses = self.process_qcow2_rootfses([self.get_rootfs_name()])
        all_linklatencies = [self.server_link_latency]
        all_maxbws = [self.server_bw_max]
        all_bootbins = [self.get_bootbin_name()]
        all_shmemportnames = [shmemportname]

        runcommand = self.get_resolved_server_hardware_config().get_boot_simulation_command(
            slotno,
            all_macs,
            all_rootfses,
            all_linklatencies,
            all_maxbws,
            self.server_profile_interval,
            all_bootbins,
            all_shmemportnames,
            self.tracerv_config,
            self.autocounter_config,
            self.hostdebug_config,
            self.synthprint_config,
            sudo,
            self.plusarg_passthrough)

        return runcommand

    def get_local_job_results_dir_path(self) -> str:
        """ Return local job results directory path. e.g.:
        results-workload/workloadname/jobname/
        """
        jobinfo = self.get_job()
        job_results_dir = self.get_job().parent_workload.job_results_dir
        job_dir = f"{job_results_dir}/{jobinfo.jobname}/"
        return job_dir

    def get_local_job_monitoring_file_path(self) -> str:
        """ Return local job monitoring file path. e.g.:
        results-workload/workloadname/.monitoring-dir/jobname
        """
        jobinfo = self.get_job()
        job_monitoring_dir = self.get_job().parent_workload.job_monitoring_dir
        job_monitoring_file = """{}/{}""".format(job_monitoring_dir, jobinfo.jobname)
        return job_monitoring_file

    def write_job_complete_file(self) -> None:
        """ Write file that signals to monitoring flow that job is complete. """
        with open(self.get_local_job_monitoring_file_path(), 'w') as lfile:
            lfile.write("Done\n")

    def mkdir_and_prep_local_job_results_dir(self) -> None:
        """ Mkdir local job results directory and write any pre-sim metadata.
        """
        job_dir = self.get_local_job_results_dir_path()
        localcap = local("""mkdir -p {}""".format(job_dir), capture=True)
        rootLogger.debug("[localhost] " + str(localcap))
        rootLogger.debug("[localhost] " + str(localcap.stderr))

        # add hw config summary per job
        localcap = local("""echo "{}" > {}/HW_CFG_SUMMARY""".format(str(self.server_hardware_config), job_dir), capture=True)
        rootLogger.debug("[localhost] " + str(localcap))
        rootLogger.debug("[localhost] " + str(localcap.stderr))

    def write_script(self, script_name, command) -> str:
        """ Write a script named script_name to the local job results dir with
        shebang + command + newline. Return the full local path."""
        job_dir = self.get_local_job_results_dir_path()
        script_path = job_dir + script_name

        with open(script_path, 'w') as lfile:
            lfile.write("#!/usr/bin/env bash\n")
            lfile.write(command)
            lfile.write("\n")

        return script_path

    def write_sim_start_script(self, slotno: int, sudo: bool) -> str:
        """ Write sim-run.sh script to local job results dir and return its
        path. """
        start_cmd = self.get_sim_start_command(slotno, sudo)
        sim_start_script_local_path = self.write_script("sim-run.sh", start_cmd)
        return sim_start_script_local_path

    def copy_back_job_results_from_run(self, slotno: int, sudo: bool) -> None:
        """
        1) Copy back UART log
        2) Mount rootfs on the remote node and copy back files
        """
        assert self.has_assigned_host_instance(), "copy requires assigned host instance"

        # rsync_project defaults to using -a and that will copy symlinks as links
        # and preserve group ownership and permissions.
        copy_back_extra_opts = ' '.join([
            '-L', # transform symlink into referent file/dir
            '--no-group', # use default group here for creating local files
            '--no-perms --chmod=ugo=rwX', # obey local umask
        ])

        jobinfo = self.get_job()
        job_dir = self.get_local_job_results_dir_path()

        self.write_job_complete_file()

        dest_sim_dir = self.get_host_instance().get_sim_dir()
        dest_sim_slot_dir = f"{dest_sim_dir}/sim_slot_{slotno}/"

        def mount(img: str, mnt: str, tmp_dir: str) -> None:
            if sudo:
                run(f"sudo mount -o loop {img} {mnt}")
                run(f"sudo chown -R $(whoami) {mnt}")
            else:
                run(f"""screen -S guestmount-wait -dm bash -c "guestmount -o uid=$(id -u) -o gid=$(id -g) --pid-file {tmp_dir}/guestmount.pid -a {img} -m /dev/sda {mnt}; while true; do sleep 1; done;" """, pty=False)
                try:
                    run(f"""while [ ! "$(ls -A {mnt})" ]; do echo "Waiting for mount to finish"; sleep 1; done""", timeout=60*10)
                except CommandTimeout:
                    umount(mnt, tmp_dir)

        def umount(mnt: str, tmp_dir: str) -> None:
            if sudo:
                run(f"sudo umount {mnt}")
            else:
                pid = run(f"cat {tmp_dir}/guestmount.pid")
                run("screen -XS guestmount-wait quit")
                run(f"guestunmount {mnt}")
                run(f"tail --pid={pid} -f /dev/null")
                run(f"rm -f {tmp_dir}/guestmount.pid")

        # mount rootfs, copy files from it back to local system
        rfsname = self.get_rootfs_name()
        if rfsname is not None:
            is_qcow2 = rfsname.endswith(".qcow2")
            mountpoint = dest_sim_slot_dir + "mountpoint"
            
            run("""{} mkdir -p {}""".format("sudo" if sudo else "", mountpoint))

            if is_qcow2:
                host_inst = self.get_host_instance()
                assert isinstance(host_inst.instance_deploy_manager, EC2InstanceDeployManager)
                nbd_tracker = host_inst.instance_deploy_manager.nbd_tracker
                assert nbd_tracker is not None
                rfsname = nbd_tracker.get_nbd_for_imagename(rfsname)
            else:
                rfsname = dest_sim_slot_dir + rfsname

            mount(rfsname, mountpoint, dest_sim_slot_dir)
            with warn_only(), hide('warnings'):
                # ignore if this errors. not all rootfses have /etc/sysconfig/nfs
                run("""{} chattr -i {}/etc/sysconfig/nfs""".format("sudo" if sudo else "", mountpoint))

            ## copy back files from inside the rootfs
            with warn_only():
                for outputfile in jobinfo.outputs:
                    rsync_cap = rsync_project(remote_dir=mountpoint + outputfile,
                            local_dir=job_dir,
                            ssh_opts="-o StrictHostKeyChecking=no",
                            extra_opts=copy_back_extra_opts,
                            upload=False,
                            capture=True)
                    rootLogger.debug(rsync_cap)
                    rootLogger.debug(rsync_cap.stderr)

            ## unmount
            umount(mountpoint, dest_sim_slot_dir)

            ## if qcow2, detach .qcow2 image from the device, we're done with it
            if is_qcow2:
                run("""sudo qemu-nbd -d {devname}""".format(devname=rfsname))


        ## copy output files generated by the simulator that live on the host:
        ## e.g. uartlog, memory_stats.csv, etc
        remote_sim_run_dir = dest_sim_slot_dir
        for simoutputfile in jobinfo.simoutputs:
            with warn_only():
                rsync_cap = rsync_project(remote_dir=remote_sim_run_dir + simoutputfile,
                        local_dir=job_dir,
                        ssh_opts="-o StrictHostKeyChecking=no",
                        extra_opts=copy_back_extra_opts,
                        upload=False,
                        capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

    def get_sim_kill_command(self, slotno: int) -> str:
        """ return the command to kill the simulation. assumes it will be
        called in a directory where its required_files are already located.
        """
        return self.get_resolved_server_hardware_config().get_kill_simulation_command()

    def get_tarball_files_paths(self) -> List[Tuple[str, str]]:
        """ Return local and remote paths of all stuff destined for the driver tarball 
        The returned paths in the tuple are [local_path, remote_path]. When remote_path 
        is an empty string the local filename is used."""
        all_paths = []

        driver_path = self.get_resolved_server_hardware_config().get_local_driver_path()
        all_paths.append((driver_path, ''))

        runtime_conf_path = self.get_resolved_server_hardware_config().get_local_runtime_conf_path()
        if runtime_conf_path is not None:
            all_paths.append((runtime_conf_path, ''))

        # shared libraries
        all_paths += get_local_shared_libraries(driver_path)
        all_paths += self.get_resolved_server_hardware_config().get_additional_required_sim_files()

        all_paths += self.get_job().get_siminputs()
        return all_paths
    
    def get_tarball_path_pair(self) -> Tuple[str, str]:
        """ Return local and remote paths of the actual driver tarball, not files inside"""

        if not isinstance(self.server_hardware_config, str) and self.server_hardware_config is not None and self.server_hardware_config.driver_tar is not None:
            return (self.server_hardware_config.driver_tar, self.get_tar_name())
        else:
            return (str(self.get_resolved_server_hardware_config().local_tarball_path(self.get_tar_name())), self.get_tar_name())



    def get_required_files_local_paths(self) -> List[Tuple[str, str]]:
        """ Return local and remote paths of all stuff needed to run this simulation as
        an array. The returned paths in the tuple are [local_path, remote_path]. """
        all_paths = []

        job_rootfs_path = self.get_job().rootfs_path()
        if job_rootfs_path is not None:
            self_rootfs_name = self.get_rootfs_name()
            assert self_rootfs_name is not None
            all_paths.append((job_rootfs_path, self_rootfs_name))

        all_paths.append((self.get_job().bootbinary_path(), self.get_bootbin_name()))

        all_paths.append(self.get_tarball_path_pair())

        return all_paths

    def get_agfi(self) -> str:
        """ Return the AGFI that should be flashed. """
        agfi = self.get_resolved_server_hardware_config().agfi
        assert agfi is not None
        return agfi

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

    def get_tar_name(self) -> str:
        """ Get the name of the tarball on the run host"""
        return "driver-bundle.tar.gz"

    def get_all_rootfs_names(self) -> List[Optional[str]]:
        """ Get all rootfs filenames as a list. """
        return [self.get_rootfs_name()]

    def qcow2_support_required(self) -> bool:
        """ Return True iff any rootfses for this sim require QCOW2 support, as
        determined by their filename ending (.qcow2). """
        return any(map(lambda x: x is not None and x.endswith(".qcow2"), self.get_all_rootfs_names()))

    def get_bootbin_name(self) -> str:
        # prefix bootbin name with the job name to disambiguate in supernode
        # cases
        return self.get_job_name() + "-" + self.get_job().bootbinary_path().split("/")[-1]


class FireSimSuperNodeServerNode(FireSimServerNode):
    """ This is the main server node for supernode mode. This knows how to call
    out to dummy server nodes to get all the info to run the single sim binary
    that models the N > 1 copies of a design present in a single simulator
    (e.g. a single FPGA or single metasim) in supernode mode."""

    def __init__(self) -> None:
        super().__init__()

    def copy_back_job_results_from_run(self, slotno: int, sudo: bool) -> None:
        """ This override is to call copy back job results for all the dummy nodes too. """
        # first call the original
        super().copy_back_job_results_from_run(slotno, sudo)

        # call on all siblings
        num_siblings = self.supernode_get_num_siblings_plus_one()

        # TODO: for now, just hackishly give the siblings a host node.
        # fixing this properly is going to probably require a larger revamp
        # of supernode handling
        super_server_host = self.get_host_instance()
        for sibindex in range(1, num_siblings):
            sib = self.supernode_get_sibling(sibindex)
            sib.assign_host_instance(super_server_host)
            sib.copy_back_job_results_from_run(slotno, sudo)

    def supernode_get_num_siblings_plus_one(self) -> int:
        """ This returns the number of siblings the supernodeservernode has,
        plus one (because in most places, we use siblings + 1, not just siblings)
        """
        siblings = 1
        count = False
        for index, servernode in enumerate(map(lambda x : x.get_downlink_side(), self.uplinks[0].get_uplink_side().downlinks)):
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
        for index, servernode in enumerate(map(lambda x : x.get_downlink_side(), self.uplinks[0].get_uplink_side().downlinks)):
            if self == servernode:
                node = self.uplinks[0].get_uplink_side().downlinks[index+siblingindex].get_downlink_side()
                assert isinstance(node, FireSimDummyServerNode)
                return node
        assert False, "Should return supernode sibling"

    def get_all_rootfs_names(self) -> List[Optional[str]]:
        """ Get all rootfs filenames as a list. """
        num_siblings = self.supernode_get_num_siblings_plus_one()
        return [self.get_rootfs_name()] + [self.supernode_get_sibling(x).get_rootfs_name() for x in range(1, num_siblings)]

    def get_sim_start_command(self, slotno: int, sudo: bool) -> str:
        """ get the command to run a simulation. assumes it will be
        called in a directory where its required_files are already located."""

        num_siblings = self.supernode_get_num_siblings_plus_one()

        assert self.server_link_latency is not None
        assert self.server_bw_max is not None
        assert self.server_profile_interval is not None
        assert self.tracerv_config is not None
        assert self.autocounter_config is not None
        assert self.hostdebug_config is not None
        assert self.synthprint_config is not None
        assert self.plusarg_passthrough is not None

        all_macs = [self.get_mac_address()] + [self.supernode_get_sibling(x).get_mac_address() for x in range(1, num_siblings)]
        all_rootfses = self.process_qcow2_rootfses(self.get_all_rootfs_names())
        all_bootbins = [self.get_bootbin_name()] + [self.supernode_get_sibling(x).get_bootbin_name() for x in range(1, num_siblings)]
        all_linklatencies = [self.server_link_latency]
        for x in range(1, num_siblings):
            sibling = self.supernode_get_sibling(x)
            assert sibling.server_link_latency is not None
            all_linklatencies.append(sibling.server_link_latency)
        all_maxbws = [self.server_bw_max]
        for x in range(1, num_siblings):
            sibling = self.supernode_get_sibling(x)
            assert sibling.server_bw_max is not None
            all_maxbws.append(sibling.server_bw_max)

        all_shmemportnames = ["default" for x in range(num_siblings)]
        if self.uplinks:
            all_shmemportnames = [self.uplinks[0].get_global_link_id()] + [self.supernode_get_sibling(x).uplinks[0].get_global_link_id() for x in range(1, num_siblings)]

        runcommand = self.get_resolved_server_hardware_config().get_boot_simulation_command(
            slotno,
            all_macs,
            all_rootfses,
            all_linklatencies,
            all_maxbws,
            self.server_profile_interval,
            all_bootbins,
            all_shmemportnames,
            self.tracerv_config,
            self.autocounter_config,
            self.hostdebug_config,
            self.synthprint_config,
            sudo,
            self.plusarg_passthrough)

        return runcommand

    def get_required_files_local_paths(self) -> List[Tuple[str, str]]:
        """ Return local paths of all stuff needed to run this simulation as
        an array. """

        def get_path_trailing(filepath):
            return filepath.split("/")[-1]
        def local_and_remote(filepath, index):
            return [filepath, get_path_trailing(filepath) + str(index)]

        hw_cfg = self.get_resolved_server_hardware_config()

        all_paths = []
        job_rootfs_path = self.get_job().rootfs_path()
        if job_rootfs_path is not None:
            self_rootfs_name = self.get_rootfs_name()
            assert self_rootfs_name is not None
            all_paths.append((job_rootfs_path, self_rootfs_name))

        driver_path = hw_cfg.get_local_driver_path()
        all_paths.append((driver_path, ''))

        # shared libraries
        all_paths += get_local_shared_libraries(driver_path)
        all_paths += self.get_resolved_server_hardware_config().get_additional_required_sim_files()

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

        runtime_conf_path = hw_cfg.get_local_runtime_conf_path()
        if runtime_conf_path is not None:
            all_paths.append((runtime_conf_path, ''))
        return all_paths


class FireSimDummyServerNode(FireSimServerNode):
    """ This is a dummy server node for supernode mode. """

    def __init__(self, server_hardware_config: Optional[Union[RuntimeHWConfig, str]] = None, server_link_latency: Optional[int] = None,
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

    def get_switch_start_command(self, sudo: bool) -> str:
        return self.switch_builder.get_switch_simulation_command(sudo)

    def get_switch_kill_command(self) -> str:
        return self.switch_builder.kill_switch_simulation_command()

    def copy_back_switchlog_from_run(self, job_results_dir: str, switch_slot_no: int) -> None:
        """
        Copy back the switch log for this switch

        TODO: move this somewhere else, it's kinda in a weird place...
        """
        job_dir = """{}/switch{}/""".format(job_results_dir, self.switch_id_internal)

        localcap = local("""mkdir -p {}""".format(job_dir), capture=True)
        rootLogger.debug("[localhost] " + str(localcap))
        rootLogger.debug("[localhost] " + str(localcap.stderr))

        dest_sim_dir = self.get_host_instance().get_sim_dir()

        ## copy output files generated by the simulator that live on the host:
        ## e.g. uartlog, memory_stats.csv, etc
        remote_sim_run_dir = """{}/switch_slot_{}/""".format(dest_sim_dir, switch_slot_no)
        for simoutputfile in ["switchlog"]:
            get(remote_path=remote_sim_run_dir + simoutputfile, local_path=job_dir)

    def diagramstr(self) -> str:
        msg =  f"FireSimSwitchNode:{self.switch_id_internal}\n"
        msg += f"---------\n"
        msg += f"""downlinks: {", ".join(map(str, self.downlinkmacs))}\n"""
        msg += f"""switchingtable: {", ".join(map(str, self.switch_table))}"""
        return msg
