""" Run Farm management. """

from __future__ import annotations

import re
import logging
import abc
import json
from fabric.api import prefix, local, run, env, cd, warn_only, put, settings, hide # type: ignore
from fabric.contrib.project import rsync_project # type: ignore
import time
from os.path import join as pjoin
from os import PathLike, fspath
from fsspec.core import url_to_fs # type: ignore
from pathlib import Path

from util.streamlogger import StreamLogger
from awstools.awstools import terminate_instances, get_instance_ids_for_instances
from runtools.utils import has_sudo

from typing import List, Dict, Optional, Union, TYPE_CHECKING
if TYPE_CHECKING:
    from mypy_boto3_ec2.service_resource import Instance as EC2InstanceResource
    from runtools.firesim_topology_elements import FireSimSwitchNode, FireSimServerNode
    from runtools.run_farm import Inst
    from awstools.awstools import MockBoto3Instance

rootLogger = logging.getLogger()

# from  https://github.com/pandas-dev/pandas/blob/96b036cbcf7db5d3ba875aac28c4f6a678214bfb/pandas/io/common.py#L73
_RFC_3986_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9+\-+.]*://")

class NBDTracker:
    """Track allocation of NBD devices on an instance. Used for mounting
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

class InstanceDeployManager(metaclass=abc.ABCMeta):
    """Class used to represent different "run platforms" and how to start/stop and setup simulations.

    Attributes:
        parent_node: Run farm host associated with this platform implementation.
    """
    parent_node: Inst
    nbd_tracker: Optional[NBDTracker]

    def __init__(self, parent_node: Inst) -> None:
        """
        Args:
            parent_node: Run farm host to associate with this platform implementation
        """
        self.parent_node = parent_node
        self.sim_type_message = 'FPGA' if not parent_node.metasimulation_enabled else 'Metasim'
        # Set this to self.nbd_tracker = NBDTracker() in the __init__ of your
        # subclass if your system supports the NBD kernel module.
        self.nbd_tracker = None

    @abc.abstractmethod
    def infrasetup_instance(self) -> None:
        """Run platform specific implementation of how to setup simulations.

        Anything that should only be executed if prepping for an actual FPGA-based
        simulation (i.e. not metasim mode) should be gated by:

        if not self.parent_node.metasimulation_enabled:
            [FPGA-specific logic, e.g. flashing FPGAs]

        """
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_instance(self) -> None:
        """Run platform specific implementation of how to terminate host
        machines.

        Platforms that do not have a notion of terminating a machine should
        override this to do nothing.

        """
        raise NotImplementedError

    def instance_logger(self, logstr: str) -> None:
        """ Log with this host's info as prefix. """
        rootLogger.info("""[{}] """.format(env.host_string) + logstr)

    def sim_node_qcow(self) -> None:
        """ If NBD is available, install qemu-img management tools and copy NBD
        infra to remote node. This assumes that the kernel module was already
        built and exists in the directory on this machine.
        """
        if self.nbd_tracker is not None:
            self.instance_logger("""Setting up remote node for qcow2 disk images.""")
            with StreamLogger('stdout'), StreamLogger('stderr'):
                # get qemu-nbd
                ### XXX Centos Specific
                run('sudo yum -y install qemu-img')
                # copy over kernel module
                put('../build/nbd.ko', '/home/centos/nbd.ko', mirror_local_mode=True)

    def load_nbd_module(self) -> None:
        """ If NBD is available, load the nbd module. always unload the module
        first to ensure it is in a clean state. """
        if self.nbd_tracker is not None:
            self.instance_logger("Loading NBD Kernel Module.")
            self.unload_nbd_module()
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""sudo insmod /home/centos/nbd.ko nbds_max={}""".format(self.nbd_tracker.NBDS_MAX))

    def unload_nbd_module(self) -> None:
        """ If NBD is available, unload the nbd module. """
        if self.nbd_tracker is not None:
            self.instance_logger("Unloading NBD Kernel Module.")

            # disconnect all /dev/nbdX devices before rmmod
            self.disconnect_all_nbds_instance()
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                run('sudo rmmod nbd')

    def disconnect_all_nbds_instance(self) -> None:
        """ If NBD is available, disconnect all nbds on the instance. """
        if self.nbd_tracker is not None:
            self.instance_logger("Disconnecting all NBDs.")

            # warn_only, so we can call this even if there are no nbds
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                # build up one large command with all the disconnects
                fullcmd = []
                for nbd_index in range(self.nbd_tracker.NBDS_MAX):
                    fullcmd.append("""sudo qemu-nbd -d /dev/nbd{nbdno}""".format(nbdno=nbd_index))

                run("; ".join(fullcmd))

    def copy_sim_slot_infrastructure(self, slotno: int) -> None:
        """ copy all the simulation infrastructure to the remote node. """
        if self.instance_assigned_simulations():
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            serv = self.parent_node.sim_slots[slotno]

            self.instance_logger(f"""Copying {self.sim_type_message} simulation infrastructure for slot: {slotno}.""")

            remote_home_dir = self.parent_node.get_sim_dir()

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
        """ copy all the switch infrastructure to the remote node. """
        if self.instance_assigned_switches():
            self.instance_logger("""Copying switch simulation infrastructure for switch slot: {}.""".format(switchslot))
            remote_home_dir = self.parent_node.get_sim_dir()
            remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""mkdir -p {}""".format(remote_switch_dir))

            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            files_to_copy = switch.get_required_files_local_paths()
            for local_path, remote_path in files_to_copy:
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    put(local_path, pjoin(remote_switch_dir, remote_path), mirror_local_mode=True)


    def start_switch_slot(self, switchslot: int) -> None:
        """ start a switch simulation. """
        if self.instance_assigned_switches():
            self.instance_logger("""Starting switch simulation for switch slot: {}.""".format(switchslot))
            remote_home_dir = self.parent_node.get_sim_dir()
            remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            with cd(remote_switch_dir), StreamLogger('stdout'), StreamLogger('stderr'):
                run(switch.get_switch_start_command(has_sudo()))

    def start_sim_slot(self, slotno: int) -> None:
        """ start a simulation. """
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Starting {self.sim_type_message} simulation for slot: {slotno}.""")
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = """{}/sim_slot_{}/""".format(remote_home_dir, slotno)
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]
            with cd(remote_sim_dir), StreamLogger('stdout'), StreamLogger('stderr'):
                run(server.get_sim_start_command(slotno, has_sudo()))


    def kill_switch_slot(self, switchslot: int) -> None:
        """ kill the switch in slot switchslot. """
        if self.instance_assigned_switches():
            self.instance_logger("""Killing switch simulation for switchslot: {}.""".format(switchslot))
            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                if has_sudo():
                    run("sudo " + switch.get_switch_kill_command())
                else:
                    run(switch.get_switch_kill_command())

    def kill_sim_slot(self, slotno: int) -> None:
        """ kill the simulation in slot slotno. """
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Killing {self.sim_type_message} simulation for slot: {slotno}.""")
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                if has_sudo():
                    run("sudo " + server.get_sim_kill_command(slotno))
                else:
                    run(server.get_sim_kill_command(slotno))

    def instance_assigned_simulations(self) -> bool:
        """ return true if this instance has any assigned fpga simulations. """
        return len(self.parent_node.sim_slots) != 0

    def instance_assigned_switches(self) -> bool:
        """ return true if this instance has any assigned switch simulations. """
        return len(self.parent_node.switch_slots) != 0

    def start_switches_instance(self) -> None:
        """Boot up all the switches on this host in screens."""
        # remove shared mem pages used by switches
        if self.instance_assigned_switches():
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

            for slotno in range(len(self.parent_node.switch_slots)):
                self.start_switch_slot(slotno)

    def start_simulations_instance(self) -> None:
        """ Boot up all the sims on this host in screens. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(len(self.parent_node.sim_slots)):
                self.start_sim_slot(slotno)

    def kill_switches_instance(self) -> None:
        """ Kill all the switches on this host. """
        if self.instance_assigned_switches():
            for slotno in range(len(self.parent_node.switch_slots)):
                self.kill_switch_slot(slotno)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

    def kill_simulations_instance(self, disconnect_all_nbds: bool = True) -> None:
        """ Kill all simulations on this host. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(len(self.parent_node.sim_slots)):
                self.kill_sim_slot(slotno)
        if disconnect_all_nbds:
            # disconnect all NBDs
            self.disconnect_all_nbds_instance()

    def running_simulations(self) -> Dict[str, List[str]]:
        """ collect screen results from this host to see what's running on it.
        """
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
        """ Job monitoring for this host. """
        # make a local copy of completed_jobs, so that we can update it
        completed_jobs = list(completed_jobs)

        rootLogger.debug("completed jobs " + str(completed_jobs))

        if not self.instance_assigned_simulations() and self.instance_assigned_switches():
            # this node hosts ONLY switches and not sims
            #
            # just confirm that our switches are still running
            # switches will never trigger shutdown in the cycle-accurate -
            # they should run forever until torn down
            if teardown:
                # handle the case where we're just tearing down nodes that have
                # ONLY switches
                for counter in range(len(self.parent_node.switch_slots)):
                    switchsim = self.parent_node.switch_slots[counter]
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

                if terminateoncompletion:
                    # terminate the instance since teardown is called and instance
                    # termination is enabled
                    self.terminate_instance()

                # don't really care about the return val in the teardown case
                return {'switches': dict(), 'sims': dict()}

            # not teardown - just get the status of the switch sims
            switchescompleteddict = {k: False for k in self.running_simulations()['switches']}
            for switchsim in self.parent_node.switch_slots:
                swname = switchsim.switch_builder.switch_binary_name()
                if swname not in switchescompleteddict.keys():
                    switchescompleteddict[swname] = True
            return {'switches': switchescompleteddict, 'sims': dict()}

        if self.instance_assigned_simulations():
            # this node has sims attached

            # first, figure out which jobs belong to this instance.
            # if they are all completed already. RETURN, DON'T TRY TO DO ANYTHING
            # ON THE INSTNACE.
            parentslots = self.parent_node.sim_slots
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
                for switchsim in self.parent_node.switch_slots:
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
                    parent.copy_back_job_results_from_run(slotno, has_sudo())
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

                for counter, switchsim in enumerate(self.parent_node.switch_slots):
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

            if now_done and terminateoncompletion:
                # terminate the instance since everything is done and instance
                # termination is enabled
                self.terminate_instance()

            return {'switches': switchescompleteddict, 'sims': jobs_done_q}

        assert False



def remote_kmsg(message: str) -> None:
    """ This will let you write whatever is passed as message into the kernel
    log of the remote machine.  Useful for figuring what the manager is doing
    w.r.t output from kernel stuff on the remote node. """
    commd = """echo '{}' | sudo tee /dev/kmsg""".format(message)
    run(commd, shell=True)


class EC2InstanceDeployManager(InstanceDeployManager):
    """  This class manages actually deploying/running stuff based on the
    definition of an instance and the simulations/switches assigned to it.

    This is in charge of managing the locations of stuff on remote nodes.
    """
    boto3_instance_object: Optional[Union[EC2InstanceResource, MockBoto3Instance]]

    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.boto3_instance_object = None
        self.nbd_tracker = NBDTracker()

    def get_and_install_aws_fpga_sdk(self) -> None:
        """ Installs the aws-sdk. This gets us access to tools to flash the fpga. """
        if self.instance_assigned_simulations():
            with prefix('cd ../'), \
                StreamLogger('stdout'), \
                StreamLogger('stderr'):
                # use local version of aws_fpga on run farm nodes
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
        if self.instance_assigned_simulations():
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

    def unload_xrt_and_xocl(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("Unloading XRT-related Kernel Modules.")

            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                # fpga mgmt tools seem to force load xocl after a flash now...
                # so we just remove everything for good measure:
                remote_kmsg("removing_xrt_start")
                run('sudo systemctl stop mpd')
                run('sudo yum remove -y xrt xrt-aws')
                remote_kmsg("removing_xrt_end")

    def unload_xdma(self) -> None:
        if self.instance_assigned_simulations():
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
        if self.instance_assigned_simulations():
            # we always clear ALL fpga slots
            for slotno in range(self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Clearing FPGA Slot {}.""".format(slotno))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    remote_kmsg("""about_to_clear_fpga{}""".format(slotno))
                    run("""sudo fpga-clear-local-image -S {} -A""".format(slotno))
                    remote_kmsg("""done_clearing_fpga{}""".format(slotno))

            for slotno in range(self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Checking for Cleared FPGA Slot {}.""".format(slotno))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    remote_kmsg("""about_to_check_clear_fpga{}""".format(slotno))
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "cleared"; do  sleep 1;  done""".format(slotno))
                    remote_kmsg("""done_checking_clear_fpga{}""".format(slotno))


    def flash_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            dummyagfi = None
            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
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
            for slotno in range(len(self.parent_node.sim_slots), self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Flashing FPGA Slot: {} with dummy agfi: {}.""".format(slotno, dummyagfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""sudo fpga-load-local-image -S {} -I {} -A""".format(
                        slotno, dummyagfi))

            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, agfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))

            for slotno in range(len(self.parent_node.sim_slots), self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, dummyagfi))
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))


    def load_xdma(self) -> None:
        """ load the xdma kernel module. """
        if self.instance_assigned_simulations():
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
        """ start the vivado hw_server and virtual jtag on simulation instance. """
        if self.instance_assigned_simulations():
            self.instance_logger("Starting Vivado hw_server.")
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""screen -S hw_server -d -m bash -c "script -f -c 'hw_server'"; sleep 1""")
            self.instance_logger("Starting Vivado virtual JTAG.")
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""screen -S virtual_jtag -d -m bash -c "script -f -c 'sudo fpga-start-virtual-jtag -P 10201 -S 0'"; sleep 1""")

    def kill_ila_server(self) -> None:
        """ Kill the vivado hw_server and virtual jtag """
        if self.instance_assigned_simulations():
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo pkill -SIGKILL hw_server")
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo pkill -SIGKILL fpga-local-cmd")


    def infrasetup_instance(self) -> None:
        """ Handle infrastructure setup for this instance. """

        metasim_enabled = self.parent_node.metasimulation_enabled

        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno)

            if not metasim_enabled:
                self.get_and_install_aws_fpga_sdk()
                # unload any existing edma/xdma/xocl
                self.unload_xrt_and_xocl()
                # copy xdma driver
                self.fpga_node_xdma()
                # load xdma
                self.load_xdma()

            # setup nbd/qcow infra
            self.sim_node_qcow()
            # load nbd module
            self.load_nbd_module()

            if not metasim_enabled:
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
            for slotno in range(len(self.parent_node.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def terminate_instance(self) -> None:
        assert isinstance(self.boto3_instance_object, EC2InstanceResource)
        instanceids = get_instance_ids_for_instances([self.boto3_instance_object])
        terminate_instances(instanceids, dryrun=False)


class VitisInstanceDeployManager(InstanceDeployManager):
    """ This class manages a Vitis-enabled instance """
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)

    def clear_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Clearing all FPGA Slots.""")

            card_bdfs = []
            with settings(warn_only=True), hide('everything'):
                temp_file = "/tmp/xbutil-examine-out.json"
                collect = run(f"xbutil examine --format JSON -o {temp_file}")
                with open(temp_file, "r") as f:
                    json_dict = json.loads(f.read())
                card_bdfs = [d["bdf"] for d in json_dict["system"]["host"]["devices"]]

            for card_bdf in card_bdfs:
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run(f"xbutil reset -d {card_bdf} --force")

    def localize_xclbin(self, slotno: int) -> None:
        """ download xclbin URI to remote node. """
        assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
        serv = self.parent_node.sim_slots[slotno]
        hwcfg = serv.get_resolved_server_hardware_config()
        assert hwcfg.xclbin is not None
        if re.match(_RFC_3986_PATTERN, hwcfg.xclbin):
            remote_home_dir = self.parent_node.get_sim_dir()
            remote_sim_dir = f"{remote_home_dir}/sim_slot_{slotno}/"
            hwcfg.local_xclbin = './local.xclbin'

            def get_xclbin(xclbin_uri: str, xclbin_lpath: PathLike) -> None:
                # TODO consider using fsspec
                # filecache https://filesystem-spec.readthedocs.io/en/latest/features.html#caching-files-locally
                # so that multiple slots using the same xclbin only grab it once and
                # we only download it if it has changed at the source.
                # HOWEVER, 'filecache' isn't thread/process safe and I'm not sure whether
                # this runs in @parallel for fabric
                lpath = Path(xclbin_lpath)
                if lpath.exists:
                    rootLogger.debug(f"Overwriting {lpath.resolve(strict=False)}")
                rootLogger.debug(f"Downloading '{xclbin_uri}' to '{lpath}'")
                fs, rpath = url_to_fs(xclbin_uri)
                fs.get_file(rpath, fspath(lpath)) # fspath() b.c. fsspec deals in strings, not PathLike

            with cd(remote_sim_dir), StreamLogger('stdout'), StreamLogger('stderr'):
                run(get_xclbin, hwcfg.xclbin, hwcfg.local_xclbin)

        else:
            hwcfg.local_xclbin = hwcfg.xclbin



    def infrasetup_instance(self) -> None:
        """ Handle infrastructure setup for this platform. """
        metasim_enabled = self.parent_node.metasimulation_enabled

        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno)
                self.localize_xclbin(slotno)

            if not metasim_enabled:
                # clear/flash fpgas
                self.clear_fpgas()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(len(self.parent_node.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def terminate_instance(self) -> None:
        """ VitisInstanceDeployManager machines cannot be terminated. """
        pass

