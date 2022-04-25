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

rootLogger = logging.getLogger()

def remote_kmsg(message: str) -> None:
    """ This will let you write whatever is passed as message into the kernel
    log of the remote machine.  Useful for figuring what the manager is doing
    w.r.t output from kernel stuff on the remote node. """
    commd = """echo '{}' | sudo tee /dev/kmsg""".format(message)
    run(commd, shell=True)

class NBDTracker:
    """ Track allocation of NBD devices on an instance. Used for mounting
    qcow2 images."""

    # max number of NBDs allowed by the nbd.ko kernel module
    NBDS_MAX: int = 128
    unallocd: List[str]
    allocated_dict: Dict[str, str]

    def __init__(self) -> None:
        self.unallocd = ["""/dev/nbd{}""".format(x) for x in range(self.NBDS_MAX)]

        # this is a mapping from .qcow2 image name to nbd device.
        self.allocated_dict = {}

    def get_nbd_for_imagename(self, imagename: str) -> str:
        """ Call this when you need to allocate an nbd for a particular image,
        or when you need to know what nbd device is for that image.

        This will allocate an nbd for an image if one does not already exist.

        THIS DOES NOT CALL qemu-nbd to actually connect the image to the device"""
        if imagename not in self.allocated_dict.keys():
            # otherwise, allocate it
            assert len(self.unallocd) >= 1, "No NBDs left to allocate on this instance."
            self.allocated_dict[imagename] = self.unallocd.pop(0)

        return self.allocated_dict[imagename]

class MockBoto3Instance:
    """ This is used for testing without actually launching instances. """

    # don't use 0 unless you want stuff copied to your own instance.
    base_ip: int = 1
    ip_addr_int: int
    private_ip_address: str

    def __init__(self) -> None:
        self.ip_addr_int = MockBoto3Instance.base_ip
        MockBoto3Instance.base_ip += 1
        self.private_ip_address = ".".join([str((self.ip_addr_int >> (8*x)) & 0xFF) for x in [3, 2, 1, 0]])

class Inst(metaclass=abc.ABCMeta):
    # TODO: this is leftover from when we could only support switch slots.
    # This can be removed once self.switch_slots is dynamically allocated.
    # Just make it arbitrarily large for now.
    SWITCH_SLOTS: int = 100000
    switch_slots: List[FireSimSwitchNode]
    _next_port: int
    override_simulation_dir: Optional[str]
    instance_deploy_manager: Optional[InstanceDeployManager]

    def __init__(self) -> None:
        super().__init__()
        self.switch_slots = []
        self._next_port = 10000 # track ports to allocate for server switch model ports
        self.override_simulation_dir = None
        self.instance_deploy_manager = None

    @abc.abstractmethod
    def get_ip(self) -> str:
        raise NotImplementedError

    @abc.abstractmethod
    def set_ip(self, ip: str) -> None:
        raise NotImplementedError

    def set_sim_dir(self, drctry: str) -> None:
        self.override_simulation_dir = drctry

    def get_sim_dir(self) -> str:
        assert self.override_simulation_dir is not None
        return self.override_simulation_dir

    def add_switch(self, firesimswitchnode: FireSimSwitchNode) -> None:
        """ Add a switch to the next available switch slot. """
        assert len(self.switch_slots) < self.SWITCH_SLOTS
        self.switch_slots.append(firesimswitchnode)
        firesimswitchnode.assign_host_instance(self)

    def allocate_host_port(self) -> int:
        """ Allocate a port to use for something on the host. Successive calls
        will return a new port. """
        retport = self._next_port
        assert retport < 11000, "Exceeded number of ports used on host. You will need to modify your security groups to increase this value."
        self._next_port += 1
        return retport

    def is_fpga_node(self) -> bool:
        return False

class EC2Inst(Inst):
    boto3_instance_object: Optional[Union[EC2InstanceResource, MockBoto3Instance]]
    nbd_tracker: NBDTracker

    def __init__(self) -> None:
        super().__init__()
        self.boto3_instance_object = None
        self.instance_deploy_manager = EC2InstanceDeployManager(self)
        self.nbd_tracker = NBDTracker()

    def assign_boto3_instance_object(self, boto3obj: Union[EC2InstanceResource, MockBoto3Instance]) -> None:
        self.boto3_instance_object = boto3obj

    def is_bound_to_real_instance(self) -> bool:
        return self.boto3_instance_object is not None

    def get_ip(self) -> str:
        assert self.boto3_instance_object is not None # has to be duplicated to satisfy mypy
        return "centos@" + self.boto3_instance_object.private_ip_address

    def set_ip(self, ip: str) -> None:
        return

class FPGAInst(Inst):
    num_fpga_slots: int
    fpga_slots: List[FireSimServerNode]

    def __init__(self) -> None:
        super().__init__()
        self.num_fpga_slots = 0
        self.fpga_slots = []

    def get_num_fpga_slots_max(self) -> int:
        """ Get the number of fpga slots. """
        return self.num_fpga_slots

    def add_simulation(self, firesimservernode: FireSimServerNode) -> None:
        """ Add a simulation to the next available slot. """
        assert len(self.fpga_slots) < self.num_fpga_slots
        self.fpga_slots.append(firesimservernode)
        firesimservernode.assign_host_instance(self)

    def is_fpga_node(self) -> bool:
        return True

class F1Inst(FPGAInst, EC2Inst):
    instance_counter: int = 0
    instance_id: int

    def __init__(self, num_fpga_slots: int) -> None:
        super().__init__()
        self.num_fpga_slots = num_fpga_slots
        self.instance_id = F1Inst.instance_counter
        F1Inst.instance_counter += 1

        self.instance_deploy_manager = EC2InstanceDeployManager(self)

class M4_16(EC2Inst):
    instance_counter: int = 0
    instance_id: int

    def __init__(self) -> None:
        super().__init__()
        self.instance_id = M4_16.instance_counter
        M4_16.instance_counter += 1

class InstanceDeployManager(metaclass=abc.ABCMeta):
    @abc.abstractmethod
    def infrasetup_instance(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def start_switches_instance(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def start_simulations_instance(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def kill_switches_instance(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def kill_simulations_instance(self, disconnect_all_nbds: bool = True) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def monitor_jobs_instance(self, completed_jobs: List[str], teardown: bool, terminateoncompletion: bool,
            job_results_dir: str) -> Dict[str, Dict[str, bool]]:
        raise NotImplementedError

class EC2InstanceDeployManager(InstanceDeployManager):
    """  This class manages actually deploying/running stuff based on the
    definition of an instance and the simulations/switches assigned to it.

    This is in charge of managing the locations of stuff on remote nodes.
    """
    parentnode: EC2Inst

    def __init__(self, parentnode: EC2Inst) -> None:
        self.parentnode = parentnode

    def instance_logger(self, logstr: str) -> None:
        rootLogger.info("""[{}] """.format(env.host_string) + logstr)

    def get_and_install_aws_fpga_sdk(self) -> None:
        """ Installs the aws-sdk. This gets us access to tools to flash the fpga. """

        assert isinstance(self.parentnode, FPGAInst)

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

    def fpga_node_xdma(self) -> None:
        """ Copy XDMA infra to remote node. This assumes that the driver was
        already built and that a binary exists in the directory on this machine
        """

        self.instance_logger("""Copying AWS FPGA XDMA driver to remote node.""")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run('mkdir -p /home/centos/xdma/')
            put('../platforms/f1/aws-fpga/sdk/linux_kernel_drivers',
                '/home/centos/xdma/', mirror_local_mode=True)
            with cd('/home/centos/xdma/linux_kernel_drivers/xdma/'), \
                 prefix("export PATH=/usr/bin:$PATH"):
		 # prefix only needed if conda env is earlier in PATH
		 # see build-setup-nolog.sh for explanation.
                run('make clean')
                run('make')

    def fpga_node_qcow(self) -> None:
        """ Install qemu-img management tools and copy NBD infra to remote
        node. This assumes that the kernel module was already built and exists
        in the directory on this machine.
        """
        self.instance_logger("""Setting up remote node for qcow2 disk images.""")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            # get qemu-nbd
            ### XXX Centos Specific
            run('sudo yum -y install qemu-img')
            # copy over kernel module
            put('../build/nbd.ko', '/home/centos/nbd.ko', mirror_local_mode=True)

    def load_nbd_module(self) -> None:
        """ load the nbd module. always unload the module first to ensure it
        is in a clean state. """

        self.unload_nbd_module()
        # now load xdma
        self.instance_logger("Loading NBD Kernel Module.")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""sudo insmod /home/centos/nbd.ko nbds_max={}""".format(self.parentnode.nbd_tracker.NBDS_MAX))

    def unload_nbd_module(self) -> None:
        """ unload the nbd module. """
        self.instance_logger("Unloading NBD Kernel Module.")

        # disconnect all /dev/nbdX devices before rmmod
        self.disconnect_all_nbds_instance()
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run('sudo rmmod nbd')

    def disconnect_all_nbds_instance(self) -> None:
        """ Disconnect all nbds on the instance. """
        self.instance_logger("Disconnecting all NBDs.")

        # warn_only, so we can call this even if there are no nbds
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            # build up one large command with all the disconnects
            fullcmd = []
            for nbd_index in range(self.parentnode.nbd_tracker.NBDS_MAX):
                fullcmd.append("""sudo qemu-nbd -d /dev/nbd{nbdno}""".format(nbdno=nbd_index))

            run("; ".join(fullcmd))

    def unload_xrt_and_xocl(self) -> None:
        self.instance_logger("Unloading XRT-related Kernel Modules.")

        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            # fpga mgmt tools seem to force load xocl after a flash now...
            # so we just remove everything for good measure:
            remote_kmsg("removing_xrt_start")
            run('sudo systemctl stop mpd')
            run('sudo yum remove -y xrt xrt-aws')
            remote_kmsg("removing_xrt_end")

    def unload_xdma(self) -> None:
        self.instance_logger("Unloading XDMA Driver Kernel Module.")

        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            # fpga mgmt tools seem to force load xocl after a flash now...
            # so we just remove everything for good measure:
            remote_kmsg("removing_xdma_start")
            run('sudo rmmod xdma')
            remote_kmsg("removing_xdma_end")

        #self.instance_logger("Waiting 10 seconds after removing kernel modules (esp. xocl).")
        #time.sleep(10)

    def clear_fpgas(self) -> None:
        if isinstance(self.parentnode, FPGAInst):
            # we always clear ALL fpga slots
            for slotno in range(self.parentnode.get_num_fpga_slots_max()):
                self.instance_logger("""Clearing FPGA Slot {}.""".format(slotno))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    remote_kmsg("""about_to_clear_fpga{}""".format(slotno))
                    run("""sudo fpga-clear-local-image -S {} -A""".format(slotno))
                    remote_kmsg("""done_clearing_fpga{}""".format(slotno))

            for slotno in range(self.parentnode.get_num_fpga_slots_max()):
                self.instance_logger("""Checking for Cleared FPGA Slot {}.""".format(slotno))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    remote_kmsg("""about_to_check_clear_fpga{}""".format(slotno))
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "cleared"; do  sleep 1;  done""".format(slotno))
                    remote_kmsg("""done_checking_clear_fpga{}""".format(slotno))


    def flash_fpgas(self) -> None:
        if isinstance(self.parentnode, FPGAInst):
            dummyagfi = None
            for slotno, firesimservernode in enumerate(self.parentnode.fpga_slots):
                agfi = firesimservernode.get_agfi()
                dummyagfi = agfi
                self.instance_logger("""Flashing FPGA Slot: {} with agfi: {}.""".format(slotno, agfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""sudo fpga-load-local-image -S {} -I {} -A""".format(
                        slotno, agfi))

            # We only do this because XDMA hangs if some of the FPGAs on the instance
            # are left in the cleared state. So, if you're only using some of the
            # FPGAs on an instance, we flash the rest with one of your images
            # anyway. Since the only interaction we have with an FPGA right now
            # is over PCIe where the software component is mastering, this can't
            # break anything.
            for slotno in range(len(self.parentnode.fpga_slots), self.parentnode.get_num_fpga_slots_max()):
                self.instance_logger("""Flashing FPGA Slot: {} with dummy agfi: {}.""".format(slotno, dummyagfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""sudo fpga-load-local-image -S {} -I {} -A""".format(
                        slotno, dummyagfi))

            for slotno, firesimservernode in enumerate(self.parentnode.fpga_slots):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, agfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))

            for slotno in range(len(self.parentnode.fpga_slots), self.parentnode.get_num_fpga_slots_max()):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, dummyagfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))


    def load_xdma(self) -> None:
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

    def start_ila_server(self) -> None:
        """ start the vivado hw_server and virtual jtag on simulation instance.) """
        self.instance_logger("Starting Vivado hw_server.")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""screen -S hw_server -d -m bash -c "script -f -c 'hw_server'"; sleep 1""")
        self.instance_logger("Starting Vivado virtual JTAG.")
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""screen -S virtual_jtag -d -m bash -c "script -f -c 'sudo fpga-start-virtual-jtag -P 10201 -S 0'"; sleep 1""")

    def kill_ila_server(self) -> None:
        """ Kill the vivado hw_server and virtual jtag """
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run("sudo pkill -SIGKILL hw_server")
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run("sudo pkill -SIGKILL fpga-local-cmd")

    def copy_sim_slot_infrastructure(self, slotno: int) -> None:
        """ copy all the simulation infrastructure to the remote node. """

        assert isinstance(self.parentnode, FPGAInst)
        assert slotno < len(self.parentnode.fpga_slots)
        serv = self.parentnode.fpga_slots[slotno]

        self.instance_logger("""Copying FPGA simulation infrastructure for slot: {}.""".format(slotno))

        remote_home_dir = self.parentnode.get_sim_dir()

        remote_sim_dir = """{}/sim_slot_{}/""".format(remote_home_dir, slotno)
        remote_sim_rsync_dir = remote_sim_dir + "rsyncdir/"
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""mkdir -p {}""".format(remote_sim_rsync_dir))

        files_to_copy = serv.get_required_files_local_paths()
        for local_path, remote_path in files_to_copy:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                # -z --inplace
                rsync_cap = rsync_project(local_dir=local_path, remote_dir=pjoin(remote_sim_rsync_dir, remote_path),
                              ssh_opts="-o StrictHostKeyChecking=no", extra_opts="-L", capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""cp -r {}/* {}/""".format(remote_sim_rsync_dir, remote_sim_dir), shell=True)


    def copy_switch_slot_infrastructure(self, switchslot: int) -> None:
        self.instance_logger("""Copying switch simulation infrastructure for switch slot: {}.""".format(switchslot))

        remote_home_dir = self.parentnode.get_sim_dir()

        remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
        with StreamLogger('stdout'), StreamLogger('stderr'):
            run("""mkdir -p {}""".format(remote_switch_dir))

        assert switchslot < len(self.parentnode.switch_slots)
        switch = self.parentnode.switch_slots[switchslot]
        files_to_copy = switch.get_required_files_local_paths()
        for local_path, remote_path in files_to_copy:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                put(local_path, pjoin(remote_switch_dir, remote_path), mirror_local_mode=True)


    def start_switch_slot(self, switchslot: int) -> None:
        self.instance_logger("""Starting switch simulation for switch slot: {}.""".format(switchslot))

        remote_home_dir = self.parentnode.get_sim_dir()

        remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
        assert switchslot < len(self.parentnode.switch_slots)
        switch = self.parentnode.switch_slots[switchslot]
        assert switch is not None
        with cd(remote_switch_dir), StreamLogger('stdout'), StreamLogger('stderr'):
            run(switch.get_switch_start_command())

    def start_sim_slot(self, slotno: int) -> None:
        assert isinstance(self.parentnode, FPGAInst)

        self.instance_logger("""Starting FPGA simulation for slot: {}.""".format(slotno))

        remote_home_dir = self.parentnode.override_simulation_dir

        remote_sim_dir = """{}/sim_slot_{}/""".format(remote_home_dir, slotno)
        assert slotno < len(self.parentnode.fpga_slots)
        server = self.parentnode.fpga_slots[slotno]
        assert server is not None
        with cd(remote_sim_dir), StreamLogger('stdout'), StreamLogger('stderr'):
            server.run_sim_start_command(slotno)

    def kill_switch_slot(self, switchslot: int) -> None:
        """ kill the switch in slot switchslot. """
        self.instance_logger("""Killing switch simulation for switchslot: {}.""".format(switchslot))
        assert switchslot < len(self.parentnode.switch_slots)
        switch = self.parentnode.switch_slots[switchslot]
        assert switch is not None
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run(switch.get_switch_kill_command())

    def kill_sim_slot(self, slotno: int) -> None:
        assert isinstance(self.parentnode, FPGAInst)

        self.instance_logger("""Killing FPGA simulation for slot: {}.""".format(slotno))
        assert slotno < len(self.parentnode.fpga_slots)
        server = self.parentnode.fpga_slots[slotno]
        assert server is not None
        with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
            run(server.get_sim_kill_command(slotno))

    def instance_assigned_simulations(self) -> bool:
        """ return true if this instance has any assigned fpga simulations. """
        if isinstance(self.parentnode, FPGAInst):
            if len(self.parentnode.fpga_slots) > 0:
                return True
        return False

    def instance_assigned_switches(self) -> bool:
        """ return true if this instance has any assigned switch simulations. """
        return len(self.parentnode.switch_slots) > 0

    def infrasetup_instance(self) -> None:
        """ Handle infrastructure setup for this instance. """
        # check if fpga node
        if self.instance_assigned_simulations():
            # This is an FPGA-host node.

            assert isinstance(self.parentnode, FPGAInst)

            # copy fpga sim infrastructure
            for slotno in range(len(self.parentnode.fpga_slots)):
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
            for slotno in range(len(self.parentnode.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)


    def start_switches_instance(self) -> None:
        """ Boot up all the switches in a screen. """
        # remove shared mem pages used by switches
        if self.instance_assigned_switches():
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

            for slotno in range(len(self.parentnode.switch_slots)):
                self.start_switch_slot(slotno)

    def start_simulations_instance(self) -> None:
        """ Boot up all the sims in a screen. """
        if self.instance_assigned_simulations():
            assert isinstance(self.parentnode, FPGAInst)
            # only on sim nodes
            for slotno in range(len(self.parentnode.fpga_slots)):
                self.start_sim_slot(slotno)

    def kill_switches_instance(self) -> None:
        """ Kill all the switches on this instance. """
        if self.instance_assigned_switches():
            for slotno in range(len(self.parentnode.switch_slots)):
                self.kill_switch_slot(slotno)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

    def kill_simulations_instance(self, disconnect_all_nbds: bool = True) -> None:
        """ Kill all simulations on this instance. """
        if self.instance_assigned_simulations():
            assert isinstance(self.parentnode, FPGAInst)
            # only on sim nodes
            for slotno in range(len(self.parentnode.fpga_slots)):
                self.kill_sim_slot(slotno)
        if disconnect_all_nbds:
            # disconnect all NBDs
            self.disconnect_all_nbds_instance()

    def running_simulations(self) -> Dict[str, List[str]]:
        """ collect screen results from node to see what's running on it. """
        simdrivers = []
        switches = []
        with settings(warn_only=True), hide('everything'):
            collect = run('screen -ls')
            for line in collect.splitlines():
                if "(Detached)" in line or "(Attached)" in line:
                    line_stripped = line.strip()
                    if "fsim" in line:
                        re_search_results = re.search('fsim([0-9][0-9]*)', line_stripped)
                        assert re_search_results is not None
                        line_stripped = re_search_results.group(0)
                        line_stripped = line_stripped.replace('fsim', '')
                        simdrivers.append(line_stripped)
                    elif "switch" in line:
                        re_search_results = re.search('switch([0-9][0-9]*)', line_stripped)
                        assert re_search_results is not None
                        line_stripped = re_search_results.group(0)
                        switches.append(line_stripped)
        return {'switches': switches, 'simdrivers': simdrivers}

    def monitor_jobs_instance(self, completed_jobs: List[str], teardown: bool, terminateoncompletion: bool,
            job_results_dir: str) -> Dict[str, Dict[str, bool]]:
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
                for counter in range(len(self.parentnode.switch_slots)):
                    switchsim = self.parentnode.switch_slots[counter]
                    assert switchsim is not None
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

                if terminateoncompletion:
                    # terminate the instance since teardown is called and instance
                    # termination is enabled
                    assert isinstance(self.parentnode.boto3_instance_object, EC2InstanceResource)
                    instanceids = get_instance_ids_for_instances([self.parentnode.boto3_instance_object])
                    terminate_instances(instanceids, dryrun=False)

                # don't really care about the return val in the teardown case
                return {'switches': dict(), 'sims': dict()}

            # not teardown - just get the status of the switch sims
            switchescompleteddict = {k: False for k in self.running_simulations()['switches']}
            for switchsim in self.parentnode.switch_slots:
                assert switchsim is not None
                swname = switchsim.switch_builder.switch_binary_name()
                if swname not in switchescompleteddict.keys():
                    switchescompleteddict[swname] = True
            return {'switches': switchescompleteddict, 'sims': dict()}

        if self.instance_assigned_simulations():
            # this node has fpga sims attached

            assert isinstance(self.parentnode, FPGAInst)

            # first, figure out which jobs belong to this instance.
            # if they are all completed already. RETURN, DON'T TRY TO DO ANYTHING
            # ON THE INSTNACE.
            parentslots = self.parentnode.fpga_slots
            rootLogger.debug("parentslots " + str(parentslots))
            jobnames = [slot.get_job_name() for slot in parentslots if slot is not None]
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
                for switchsim in self.parentnode.switch_slots:
                    assert switchsim is not None
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
                    parent = parentslots[slotno]
                    assert parent is not None
                    parent.copy_back_job_results_from_run(slotno)
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

                for counter, switchsim in enumerate(self.parentnode.switch_slots):
                    assert switchsim is not None
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

            if now_done and terminateoncompletion:
                # terminate the instance since everything is done and instance
                # termination is enabled
                assert isinstance(self.parentnode.boto3_instance_object, EC2InstanceResource)
                instanceids = get_instance_ids_for_instances([self.parentnode.boto3_instance_object])
                terminate_instances(instanceids, dryrun=False)

            return {'switches': switchescompleteddict, 'sims': jobs_done_q}

        # default return
        return {'switches': dict(), 'sims': dict()}
