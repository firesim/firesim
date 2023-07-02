""" Run Farm management. """

from __future__ import annotations

import re
import logging
import abc
import json
from fabric.api import prefix, local, run, env, cd, warn_only, put, settings, hide # type: ignore
from fabric.contrib.project import rsync_project # type: ignore
from os.path import join as pjoin
import os

from util.streamlogger import StreamLogger
from awstools.awstools import terminate_instances, get_instance_ids_for_instances
from runtools.utils import has_sudo

from typing import List, Dict, Optional, Union, Tuple, TYPE_CHECKING
if TYPE_CHECKING:
    from runtools.run_farm import Inst
    from awstools.awstools import MockBoto3Instance

rootLogger = logging.getLogger()

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
    def infrasetup_instance(self, uridir: str) -> None:
        """Run platform specific implementation of how to setup simulations.

        Anything that should only be executed if prepping for an actual FPGA-based
        simulation (i.e. not metasim mode) should be gated by:

        if not self.parent_node.metasimulation_enabled:
            [FPGA-specific logic, e.g. flashing FPGAs]

        """
        raise NotImplementedError

    @abc.abstractmethod
    def enumerate_fpgas(self, uridir: str) -> None:
        """Run platform specific implementation of how to enumerate FPGAs for FireSim."""
        raise NotImplementedError

    @abc.abstractmethod
    def terminate_instance(self) -> None:
        """Run platform specific implementation of how to terminate host
        machines.

        Platforms that do not have a notion of terminating a machine should
        override this to do nothing.

        """
        raise NotImplementedError

    @classmethod
    @abc.abstractmethod
    def sim_command_requires_sudo(cls) -> bool:
        """ Set when the sim start command must be launched with sudo. """
        raise NotImplementedError

    def instance_logger(self, logstr: str, debug: bool = False) -> None:
        """ Log with this host's info as prefix. """
        if debug:
            rootLogger.debug("""[{}] """.format(env.host_string) + logstr)
        else:
            rootLogger.info("""[{}] """.format(env.host_string) + logstr)

    def sim_node_qcow(self) -> None:
        """ If NBD is available and qcow2 support is required, install qemu-img
        management tools and copy NBD infra to remote node. This assumes that
        the kernel module was already built and exists in the directory on this
        machine. """
        if self.nbd_tracker is not None and self.parent_node.qcow2_support_required():
            self.instance_logger("""Setting up remote node for qcow2 disk images.""")
            # get qemu-nbd
            ### XXX Centos Specific
            run('sudo yum -y install qemu-img')
            # copy over kernel module
            put('../build/nbd.ko', f"/home/{os.environ['USER']}/nbd.ko", mirror_local_mode=True)

    def load_nbd_module(self) -> None:
        """ If NBD is available and qcow2 support is required, load the nbd
        module. always unload the module first to ensure it is in a clean
        state. """
        if self.nbd_tracker is not None and self.parent_node.qcow2_support_required():
            self.instance_logger("Loading NBD Kernel Module.")
            self.unload_nbd_module()
            run(f"""sudo insmod /home/{os.environ['USER']}/nbd.ko nbds_max={self.nbd_tracker.NBDS_MAX}""")

    def unload_nbd_module(self) -> None:
        """ If NBD is available and qcow2 support is required, unload the nbd
        module. """
        if self.nbd_tracker is not None and self.parent_node.qcow2_support_required():
            self.instance_logger("Unloading NBD Kernel Module.")

            # disconnect all /dev/nbdX devices before rmmod
            self.disconnect_all_nbds_instance()
            with warn_only():
                run('sudo rmmod nbd')

    def disconnect_all_nbds_instance(self) -> None:
        """ If NBD is available and qcow2 support is required, disconnect all
        nbds on the instance. """
        if self.nbd_tracker is not None and self.parent_node.qcow2_support_required():
            self.instance_logger("Disconnecting all NBDs.")

            # warn_only, so we can call this even if there are no nbds
            with warn_only():
                # build up one large command with all the disconnects
                fullcmd = []
                for nbd_index in range(self.nbd_tracker.NBDS_MAX):
                    fullcmd.append("""sudo qemu-nbd -d /dev/nbd{nbdno}""".format(nbdno=nbd_index))

                run("; ".join(fullcmd))

    def get_remote_sim_dir_for_slot(self, slotno: int) -> str:
        """ Returns the path on the remote for a given slot number. """
        remote_home_dir = self.parent_node.get_sim_dir()
        remote_sim_dir = f"{remote_home_dir}/sim_slot_{slotno}/"

        # so that callers can reliably concatenate folders to the returned value
        assert remote_sim_dir[-1] == '/', f"Return value of get_remote_sim_dir_for_slot({slotno}) must end with '/'."

        return remote_sim_dir

    def copy_sim_slot_infrastructure(self, slotno: int, uridir: str) -> None:
        """ copy all the simulation infrastructure to the remote node. """
        if self.instance_assigned_simulations():
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            serv = self.parent_node.sim_slots[slotno]

            self.instance_logger(f"""Copying {self.sim_type_message} simulation infrastructure for slot: {slotno}.""")

            remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
            remote_sim_rsync_dir = remote_sim_dir + "rsyncdir/"
            run(f"mkdir -p {remote_sim_rsync_dir}")

            files_to_copy = serv.get_required_files_local_paths()

            # Append required URI paths to the end of this list
            hwcfg = serv.get_resolved_server_hardware_config()
            files_to_copy.extend(hwcfg.get_local_uri_paths(uridir))

            for local_path, remote_path in files_to_copy:
                # -z --inplace
                rsync_cap = rsync_project(local_dir=local_path, remote_dir=pjoin(remote_sim_rsync_dir, remote_path),
                            ssh_opts="-o StrictHostKeyChecking=no", extra_opts="-L", capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

            run(f"cp -r {remote_sim_rsync_dir}/* {remote_sim_dir}/", shell=True)

    def extract_driver_tarball(self, slotno: int) -> None:
        """ extract tarball that already exists on the remote node. """
        if self.instance_assigned_simulations():
            assert slotno < len(self.parent_node.sim_slots)
            serv = self.parent_node.sim_slots[slotno]

            hwcfg = serv.get_resolved_server_hardware_config()

            remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
            options = "-xf"

            with cd(remote_sim_dir):
                run(f"tar {options} {hwcfg.get_driver_tar_filename()}")

    def copy_switch_slot_infrastructure(self, switchslot: int) -> None:
        """ copy all the switch infrastructure to the remote node. """
        if self.instance_assigned_switches():
            self.instance_logger("""Copying switch simulation infrastructure for switch slot: {}.""".format(switchslot))
            remote_home_dir = self.parent_node.get_sim_dir()
            remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
            run("""mkdir -p {}""".format(remote_switch_dir))

            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            files_to_copy = switch.get_required_files_local_paths()
            for local_path, remote_path in files_to_copy:
                put(local_path, pjoin(remote_switch_dir, remote_path), mirror_local_mode=True)


    def start_switch_slot(self, switchslot: int) -> None:
        """ start a switch simulation. """
        if self.instance_assigned_switches():
            self.instance_logger("""Starting switch simulation for switch slot: {}.""".format(switchslot))
            remote_home_dir = self.parent_node.get_sim_dir()
            remote_switch_dir = """{}/switch_slot_{}/""".format(remote_home_dir, switchslot)
            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            with cd(remote_switch_dir):
                run(switch.get_switch_start_command(has_sudo()))

    def start_sim_slot(self, slotno: int) -> None:
        """ start a simulation. """
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Starting {self.sim_type_message} simulation for slot: {slotno}.""")
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = f"""{remote_home_dir}/sim_slot_{slotno}/"""
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]

            # make the local job results dir for this sim slot
            server.mkdir_and_prep_local_job_results_dir()
            sim_start_script_local_path = server.write_sim_start_script(slotno, (self.sim_command_requires_sudo() and has_sudo()), f"+slotid={slotno}")
            put(sim_start_script_local_path, remote_sim_dir)

            with cd(remote_sim_dir):
                run("chmod +x sim-run.sh")
                run("./sim-run.sh")


    def kill_switch_slot(self, switchslot: int) -> None:
        """ kill the switch in slot switchslot. """
        if self.instance_assigned_switches():
            self.instance_logger("""Killing switch simulation for switchslot: {}.""".format(switchslot))
            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            with warn_only():
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
            with warn_only():
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

    def remove_shm_files(self) -> None:
        if has_sudo():
            run("sudo rm -rf /dev/shm/*")
        else:
            run("find /dev/shm -user $UID -exec rm -rf {} \;")

    def start_switches_instance(self) -> None:
        """Boot up all the switches on this host in screens."""
        # remove shared mem pages used by switches
        if self.instance_assigned_switches():
            self.remove_shm_files()
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
            self.remove_shm_files()

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

    def monitor_jobs_instance(self,
            prior_completed_jobs: List[str],
            is_final_loop: bool,
            is_networked: bool,
            terminateoncompletion: bool,
            job_results_dir: str) -> Dict[str, Dict[str, bool]]:
        """ Job monitoring for this host. """
        self.instance_logger(f"Final loop?: {is_final_loop} Is networked?: {is_networked} Terminateoncomplete: {terminateoncompletion}", debug=True)
        self.instance_logger(f"Prior completed jobs: {prior_completed_jobs}", debug=True)

        def do_terminate():
            if (not is_networked) or (is_networked and is_final_loop):
                if terminateoncompletion:
                    self.terminate_instance()


        if not self.instance_assigned_simulations() and self.instance_assigned_switches():
            self.instance_logger(f"Polling switch-only node", debug=True)

            # just confirm that our switches are still running
            # switches will never trigger shutdown in the cycle-accurate -
            # they should run forever until torn down
            if is_final_loop:
                self.instance_logger(f"Completing copies for switch-only node", debug=True)

                for counter in range(len(self.parent_node.switch_slots)):
                    switchsim = self.parent_node.switch_slots[counter]
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

                do_terminate()

                return {'switches': {}, 'sims': {}}
            else:
                # get the status of the switch sims
                switchescompleteddict = {k: False for k in self.running_simulations()['switches']}
                for switchsim in self.parent_node.switch_slots:
                    swname = switchsim.switch_builder.switch_binary_name()
                    if swname not in switchescompleteddict.keys():
                        switchescompleteddict[swname] = True

                return {'switches': switchescompleteddict, 'sims': {}}

        if self.instance_assigned_simulations():
            # this node has sims attached
            self.instance_logger(f"Polling node with simulations (and potentially switches)", debug=True)


            sim_slots = self.parent_node.sim_slots
            jobnames = [slot.get_job_name() for slot in sim_slots]
            all_jobs_completed = all([(job in prior_completed_jobs) for job in jobnames])

            self.instance_logger(f"jobnames: {jobnames}", debug=True)
            self.instance_logger(f"All jobs completed?: {all_jobs_completed}", debug=True)

            if all_jobs_completed:
                do_terminate()

                # in this case, all of the nodes jobs have already completed. do nothing.
                # this can never happen in the cycle-accurate case at a point where we care
                # about switch status, so don't bother to populate it
                jobnames_to_completed = {jname: True for jname in jobnames}
                return {'sims': jobnames_to_completed, 'switches': {}}

            # at this point, all jobs are NOT completed. so, see how they're doing now:
            instance_screen_status = self.running_simulations()

            switchescompleteddict = {k: False for k in instance_screen_status['switches']}
            slotsrunning = [x for x in instance_screen_status['simdrivers']]
            self.instance_logger(f"Switch Slots running: {switchescompleteddict}", debug=True)
            self.instance_logger(f"Sim Slots running: {slotsrunning}", debug=True)

            if self.instance_assigned_switches():
                # fill in whether switches have terminated
                for switchsim in self.parent_node.switch_slots:
                    sw_name = switchsim.switch_builder.switch_binary_name()
                    if sw_name not in switchescompleteddict.keys():
                        switchescompleteddict[sw_name] = True

            # fill in whether sims have terminated
            completed_jobs = prior_completed_jobs.copy() # create local copy to append to
            for slotno, jobname in enumerate(jobnames):
                if (str(slotno) not in slotsrunning) and (jobname not in completed_jobs):
                    self.instance_logger(f"Slot {slotno}, Job {jobname} completed!")
                    completed_jobs.append(jobname)

                    # this writes the job monitoring file
                    sim_slots[slotno].copy_back_job_results_from_run(slotno, has_sudo())

            jobs_complete_dict = {job: job in completed_jobs for job in jobnames}
            now_all_jobs_complete = all(jobs_complete_dict.values())
            self.instance_logger(f"Now done?: {now_all_jobs_complete}", debug=True)

            if now_all_jobs_complete:
                if self.instance_assigned_switches():
                    # we have switches running here, so kill them,
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

                    for counter, switch_slot in enumerate(self.parent_node.switch_slots):
                        switch_slot.copy_back_switchlog_from_run(job_results_dir, counter)

                do_terminate()

            return {'switches': switchescompleteddict, 'sims': jobs_complete_dict}

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

    @classmethod
    def sim_command_requires_sudo(cls) -> bool:
        """ This sim requires sudo. """
        return True

    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.nbd_tracker = NBDTracker()

    def get_and_install_aws_fpga_sdk(self) -> None:
        """ Installs the aws-sdk. This gets us access to tools to flash the fpga. """
        if self.instance_assigned_simulations():
            with prefix('cd ../'):
                # use local version of aws_fpga on run farm nodes
                aws_fpga_upstream_version = local('git -C platforms/f1/aws-fpga describe --tags --always --dirty', capture=True)
                if "-dirty" in aws_fpga_upstream_version:
                    rootLogger.critical("Unable to use local changes to aws-fpga. Continuing without them.")
            self.instance_logger("""Installing AWS FPGA SDK on remote nodes. Upstream hash: {}""".format(aws_fpga_upstream_version))
            with warn_only():
                run('git clone https://github.com/aws/aws-fpga')
                run('cd aws-fpga && git checkout ' + aws_fpga_upstream_version)
            with cd(f"/home/{os.environ['USER']}/aws-fpga"):
                run('source sdk_setup.sh')

    def fpga_node_xdma(self) -> None:
        """ Copy XDMA infra to remote node. This assumes that the driver was
        already built and that a binary exists in the directory on this machine
        """
        if self.instance_assigned_simulations():
            self.instance_logger("""Copying AWS FPGA XDMA driver to remote node.""")
            run(f"mkdir -p /home/{os.environ['USER']}/xdma/")
            put('../platforms/f1/aws-fpga/sdk/linux_kernel_drivers',
                f"/home/{os.environ['USER']}/xdma/", mirror_local_mode=True)
            with cd(f"/home/{os.environ['USER']}/xdma/linux_kernel_drivers/xdma/"), \
                prefix("export PATH=/usr/bin:$PATH"):
                # prefix only needed if conda env is earlier in PATH
                # see build-setup-nolog.sh for explanation.
                run('make clean')
                run('make')

    def unload_xrt_and_xocl(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("Unloading XRT-related Kernel Modules.")

            with warn_only():
                # fpga mgmt tools seem to force load xocl after a flash now...
                # so we just remove everything for good measure:
                remote_kmsg("removing_xrt_start")
                run('sudo systemctl stop mpd')
                run('sudo yum remove -y xrt xrt-aws')
                remote_kmsg("removing_xrt_end")

    def unload_xdma(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("Unloading XDMA Driver Kernel Module.")

            with warn_only():
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
                remote_kmsg("""about_to_clear_fpga{}""".format(slotno))
                run("""sudo fpga-clear-local-image -S {} -A""".format(slotno))
                remote_kmsg("""done_clearing_fpga{}""".format(slotno))

            for slotno in range(self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Checking for Cleared FPGA Slot {}.""".format(slotno))
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
                run("""sudo fpga-load-local-image -S {} -I {} -A""".format(
                    slotno, dummyagfi))

            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, agfi))
                run("""until sudo fpga-describe-local-image -S {} -R -H | grep -q "loaded"; do  sleep 1;  done""".format(slotno))

            for slotno in range(len(self.parent_node.sim_slots), self.parent_node.MAX_SIM_SLOTS_ALLOWED):
                self.instance_logger("""Checking for Flashed FPGA Slot: {} with agfi: {}.""".format(slotno, dummyagfi))
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
            run(f"sudo insmod /home/{os.environ['USER']}/xdma/linux_kernel_drivers/xdma/xdma.ko poll_mode=1")

    def start_ila_server(self) -> None:
        """ start the vivado hw_server and virtual jtag on simulation instance. """
        if self.instance_assigned_simulations():
            self.instance_logger("Starting Vivado hw_server.")
            run("""screen -S hw_server -d -m bash -c "script -f -c 'hw_server'"; sleep 1""")
            self.instance_logger("Starting Vivado virtual JTAG.")
            run("""screen -S virtual_jtag -d -m bash -c "script -f -c 'sudo fpga-start-virtual-jtag -P 10201 -S 0'"; sleep 1""")

    def kill_ila_server(self) -> None:
        """ Kill the vivado hw_server and virtual jtag """
        if self.instance_assigned_simulations():
            with warn_only():
                run("sudo pkill -SIGKILL hw_server")
            with warn_only():
                run("sudo pkill -SIGKILL fpga-local-cmd")


    def infrasetup_instance(self, uridir: str) -> None:
        """ Handle infrastructure setup for this instance. """

        metasim_enabled = self.parent_node.metasimulation_enabled

        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno, uridir)
                self.extract_driver_tarball(slotno)

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

    def enumerate_fpgas(self, uridir: str) -> None:
        """ FPGAs are enumerated already with F1 """
        return

    def terminate_instance(self) -> None:
        self.instance_logger("Terminating instance", debug=True)
        self.parent_node.terminate_self()

class VitisInstanceDeployManager(InstanceDeployManager):
    """ This class manages a Vitis-enabled instance """
    PLATFORM_NAME: str = "vitis"

    @classmethod
    def sim_command_requires_sudo(cls) -> bool:
        """ This sim does not require sudo. """
        return False

    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)

    def clear_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Clearing all FPGA Slots.""")

            card_bdfs = []
            with settings(warn_only=True), hide('everything'):
                # Examine forcibly puts the JSON in an output file (on the remote); The stdout
                # it produces is difficult to parse so use process substitution
                # to pipe JSON to stdout instead.
                # combine_stderr=False allows separate stdout and stderr streams
                xbutil_examine_json = run("xbutil examine --force --format JSON -o >(cat) > /dev/null", pty=False, combine_stderr=False)
                if xbutil_examine_json.stderr != '':
                    rootLogger.critical(f"xbutil returned:\n{xbutil_examine_json.stderr}")
                try:
                    json_dict = json.loads(xbutil_examine_json.stdout)
                except json.JSONDecodeError as e:
                    rootLogger.critical(f"JSONDecodeError when parsing output from xbutil.")
                    raise e
                card_bdfs = [d["bdf"] for d in json_dict["system"]["host"]["devices"]]

            for card_bdf in card_bdfs:
                run(f"xbutil reset -d {card_bdf} --force")

    def copy_bitstreams(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Copy bitstreams to flash.""")

            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
                serv = firesimservernode
                hwcfg = serv.get_resolved_server_hardware_config()

                bitstream_tar = hwcfg.get_bitstream_tar_filename()
                remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
                bitstream_tar_unpack_dir = f"{remote_sim_dir}/{self.PLATFORM_NAME}"
                bit = f"{remote_sim_dir}/{self.PLATFORM_NAME}/firesim.xclbin"

                # at this point the tar file is in the sim slot
                run(f"rm -rf {bitstream_tar_unpack_dir}")
                run(f"tar xvf {remote_sim_dir}/{bitstream_tar} -C {remote_sim_dir}")

    def infrasetup_instance(self, uridir: str) -> None:
        """ Handle infrastructure setup for this platform. """
        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno, uridir)
                self.extract_driver_tarball(slotno)

            if not self.parent_node.metasimulation_enabled:
                # clear/flash fpgas
                self.clear_fpgas()
                # copy bitstreams to use in run
                self.copy_bitstreams()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(len(self.parent_node.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def start_sim_slot(self, slotno: int) -> None:
        """ start a simulation. (same as default except that you pass in the bitstream file)"""
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Starting {self.sim_type_message} simulation for slot: {slotno}.""")
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]
            hwcfg = server.get_resolved_server_hardware_config()

            bit = f"{remote_sim_dir}/{self.PLATFORM_NAME}/firesim.xclbin"

            if not self.parent_node.metasimulation_enabled:
                extra_args = f"+slotid={slotno} +binary_file={bit}"
            else:
                extra_args = None

            # make the local job results dir for this sim slot
            server.mkdir_and_prep_local_job_results_dir()
            sim_start_script_local_path = server.write_sim_start_script(slotno, (self.sim_command_requires_sudo() and has_sudo()), extra_args)
            put(sim_start_script_local_path, remote_sim_dir)

            with cd(remote_sim_dir):
                run("chmod +x sim-run.sh")
                run("./sim-run.sh")

    def enumerate_fpgas(self, uridir: str) -> None:
        """ FPGAs are enumerated already with Vitis """
        return

    def terminate_instance(self) -> None:
        """ VitisInstanceDeployManager machines cannot be terminated. """
        return

class XilinxAlveoInstanceDeployManager(InstanceDeployManager):
    """ This class manages a Xilinx Alveo-enabled instance """
    PLATFORM_NAME: Optional[str]
    JSON_DB: str = "/opt/firesim-db.json"

    @classmethod
    def sim_command_requires_sudo(cls) -> bool:
        """ This sim does requires sudo. """
        return True

    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = None

    def load_xdma(self) -> None:
        """ load the xdma kernel module. """
        if self.instance_assigned_simulations():
            # load xdma if unloaded
            if run('lsmod | grep -wq xdma', warn_only=True).return_code != 0:
                self.instance_logger("Loading XDMA Driver Kernel Module.")
                # must be installed to this path on sim. machine
                run(f"sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1", shell=True)
            else:
                self.instance_logger("XDMA Driver Kernel Module already loaded.")

    def unload_xdma(self) -> None:
        """ unload the xdma kernel module. """
        if self.instance_assigned_simulations():
            # unload xdma if loaded
            if run('lsmod | grep -wq xdma', warn_only=True).return_code == 0:
                self.instance_logger("Unloading XDMA Driver Kernel Module.")
                run(f"sudo rmmod xdma", shell=True)
            else:
                self.instance_logger("XDMA Driver Kernel Module already unloaded.")

    def slot_to_bdf(self, slotno: int) -> str:
        # get fpga information from db
        self.instance_logger(f"""Determine BDF for {slotno}""")
        collect = run(f'cat {self.JSON_DB}')
        db = json.loads(collect)
        assert slotno < len(db), f"Less FPGAs available than slots ({slotno} >= {len(db)})"
        return db[slotno]['bdf']

    def flash_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Flash all FPGA Slots.""")

            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
                serv = firesimservernode
                hwcfg = serv.get_resolved_server_hardware_config()

                bitstream_tar = hwcfg.get_bitstream_tar_filename()
                remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
                bitstream_tar_unpack_dir = f"{remote_sim_dir}/{self.PLATFORM_NAME}"
                bit = f"{remote_sim_dir}/{self.PLATFORM_NAME}/firesim.bit"

                # at this point the tar file is in the sim slot
                run(f"rm -rf {bitstream_tar_unpack_dir}")
                run(f"tar xvf {remote_sim_dir}/{bitstream_tar} -C {remote_sim_dir}")

                self.instance_logger(f"""Copying FPGA flashing scripts for {slotno}""")
                rsync_cap = rsync_project(
                    local_dir=f'../platforms/{self.PLATFORM_NAME}/scripts',
                    remote_dir=remote_sim_dir,
                    ssh_opts="-o StrictHostKeyChecking=no",
                    extra_opts="-L -p",
                    capture=True)
                rootLogger.debug(rsync_cap)
                rootLogger.debug(rsync_cap.stderr)

                bdf = self.slot_to_bdf(slotno)

                self.instance_logger(f"""Flashing FPGA Slot: {slotno} ({bdf}) with bitstream: {bit}""")
                run(f"""{remote_sim_dir}/scripts/fpga-util.py --bitstream {bit} --bdf {bdf}""")

    def infrasetup_instance(self, uridir: str) -> None:
        """ Handle infrastructure setup for this platform. """
        metasim_enabled = self.parent_node.metasimulation_enabled

        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno, uridir)
                self.extract_driver_tarball(slotno)

            if not metasim_enabled:
                # unload xdma driver
                self.unload_xdma()
                # flash fpgas
                self.flash_fpgas()
                # load xdma driver
                self.load_xdma()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(len(self.parent_node.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def create_fpga_database(self, uridir: str) -> None:
        self.instance_logger(f"""Creating FPGA database""")

        remote_home_dir = self.parent_node.get_sim_dir()
        remote_sim_dir = f"{remote_home_dir}/enumerate_fpgas_staging"
        remote_sim_rsync_dir = f"{remote_sim_dir}/rsyncdir/"
        run(f"mkdir -p {remote_sim_rsync_dir}")

        # only use the collateral from 1 driver (no need to copy all things)
        assert len(self.parent_node.sim_slots) > 0
        serv = self.parent_node.sim_slots[0]

        files_to_copy = serv.get_required_files_local_paths()

        # Append required URI paths to the end of this list
        hwcfg = serv.get_resolved_server_hardware_config()
        files_to_copy.extend(hwcfg.get_local_uri_paths(uridir))

        for local_path, remote_path in files_to_copy:
            # -z --inplace
            rsync_cap = rsync_project(local_dir=local_path, remote_dir=pjoin(remote_sim_rsync_dir, remote_path),
                        ssh_opts="-o StrictHostKeyChecking=no", extra_opts="-L", capture=True)
            rootLogger.debug(rsync_cap)
            rootLogger.debug(rsync_cap.stderr)

        run(f"cp -r {remote_sim_rsync_dir}/* {remote_sim_dir}/", shell=True)

        rsync_cap = rsync_project(
            local_dir=f'../platforms/{self.PLATFORM_NAME}/scripts',
            remote_dir=remote_sim_dir + "/",
            ssh_opts="-o StrictHostKeyChecking=no",
            extra_opts="-L -p",
            capture=True)
        rootLogger.debug(rsync_cap)
        rootLogger.debug(rsync_cap.stderr)

        bitstream_tar = hwcfg.get_bitstream_tar_filename()
        bitstream_tar_unpack_dir = f"{remote_sim_dir}/{self.PLATFORM_NAME}"
        bitstream = f"{remote_sim_dir}/{self.PLATFORM_NAME}/firesim.bit"

        with cd(remote_sim_dir):
            run(f"tar -xf {hwcfg.get_driver_tar_filename()}")

        # at this point the tar file is in the sim slot
        run(f"rm -rf {bitstream_tar_unpack_dir}")
        run(f"tar xvf {remote_sim_dir}/{bitstream_tar} -C {remote_sim_dir}")

        driver = f"{remote_sim_dir}/FireSim-{self.PLATFORM_NAME}"

        with cd(remote_sim_dir):
            run(f"""./scripts/generate-fpga-db.py --bitstream {bitstream} --driver {driver} --out-db-json {self.JSON_DB}""")

    def enumerate_fpgas(self, uridir: str) -> None:
        """ Handle fpga setup for this platform. """

        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # unload xdma driver
            self.unload_xdma()
            # load xdma driver
            self.load_xdma()

            # run the passes
            self.create_fpga_database(uridir)

    def terminate_instance(self) -> None:
        """ XilinxAlveoInstanceDeployManager machines cannot be terminated. """
        return

    def start_sim_slot(self, slotno: int) -> None:
        """ start a simulation. (same as the default except that you have a mapping from slotno to a specific BDF)"""
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Starting {self.sim_type_message} simulation for slot: {slotno}.""")
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = f"""{remote_home_dir}/sim_slot_{slotno}/"""
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]

            if not self.parent_node.metasimulation_enabled:
                bdf = self.slot_to_bdf(slotno).replace('.', ':').split(':')
                extra_args = f"+domain=0x0000 +bus=0x{bdf[0]} +device=0x{bdf[1]} +function=0x{bdf[2]} +bar=0x0 +pci-vendor=0x10ee +pci-device=0x903f"
            else:
                extra_args = None

            # make the local job results dir for this sim slot
            server.mkdir_and_prep_local_job_results_dir()
            sim_start_script_local_path = server.write_sim_start_script(slotno, (self.sim_command_requires_sudo() and has_sudo()), extra_args)
            put(sim_start_script_local_path, remote_sim_dir)

            with cd(remote_sim_dir):
                run("chmod +x sim-run.sh")
                run("./sim-run.sh")

class XilinxAlveoU250InstanceDeployManager(XilinxAlveoInstanceDeployManager):
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = "xilinx_alveo_u250"

class XilinxAlveoU280InstanceDeployManager(XilinxAlveoInstanceDeployManager):
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = "xilinx_alveo_u280"

class XilinxAlveoU200InstanceDeployManager(XilinxAlveoInstanceDeployManager):
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = "xilinx_alveo_u200"

class RHSResearchNitefuryIIInstanceDeployManager(XilinxAlveoInstanceDeployManager):
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = "rhsresearch_nitefury_ii"

class XilinxVCU118InstanceDeployManager(InstanceDeployManager):
    """ This class manages a Xilinx VCU118-enabled instance using the
    garnet shell. """
    PLATFORM_NAME: Optional[str]

    @classmethod
    def sim_command_requires_sudo(cls) -> bool:
        """ This sim does requires sudo. """
        return True

    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)
        self.PLATFORM_NAME = "xilinx_vcu118"

    def load_xdma(self) -> None:
        """ load the xdma kernel module. """
        if self.instance_assigned_simulations():
            # load xdma if unloaded
            if run('lsmod | grep -wq xdma', warn_only=True).return_code != 0:
                self.instance_logger("Loading XDMA Driver Kernel Module.")
                # must be installed to this path on sim. machine
                run(f"sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1", shell=True)
            else:
                self.instance_logger("XDMA Driver Kernel Module already loaded.")

    def load_xvsec(self) -> None:
        """ load the xvsec kernel modules. """
        if self.instance_assigned_simulations():
            if run('lsmod | grep -wq xvsec', warn_only=True).return_code != 0:
                self.instance_logger("Loading XVSEC Driver Kernel Module.")
                # must be installed to this path on sim. machine
                run(f"sudo insmod /lib/modules/$(uname -r)/updates/kernel/drivers/xvsec/xvsec.ko", shell=True)
            else:
                self.instance_logger("XVSEC Driver Kernel Module already loaded.")

    def flash_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Flash all FPGA Slots.""")

            for slotno, firesimservernode in enumerate(self.parent_node.sim_slots):
                serv = self.parent_node.sim_slots[slotno]
                hwcfg = serv.get_resolved_server_hardware_config()

                bitstream_tar = hwcfg.get_bitstream_tar_filename()
                remote_sim_dir = self.get_remote_sim_dir_for_slot(slotno)
                bitstream_tar_unpack_dir = f"{remote_sim_dir}/{self.PLATFORM_NAME}"
                bit = f"{remote_sim_dir}/{self.PLATFORM_NAME}/firesim.bit"

                # at this point the tar file is in the sim slot
                run(f"rm -rf {bitstream_tar_unpack_dir}")
                run(f"tar xvf {remote_sim_dir}/{bitstream_tar} -C {remote_sim_dir}")

                self.instance_logger(f"""Determine BDF for {slotno}""")
                collect = run('lspci | grep -i xilinx')

                # TODO: is hardcoded cap 0x1 correct?
                # TODO: is "Partial Reconfig Clear File" useful (see xvsecctl help)?
                bdfs = [ { "busno": "0x" + i[:2], "devno": "0x" + i[3:5], "capno": "0x1" } for i in collect.splitlines() if len(i.strip()) >= 0 ]
                bdf = bdfs[slotno]

                busno = bdf['busno']
                devno = bdf['devno']
                capno = bdf['capno']
                self.instance_logger(f"""Flashing FPGA Slot: {slotno} (bus:{busno}, dev:{devno}, cap:{capno}) with bit: {bit}""")
                run(f"""sudo xvsecctl -b {busno} -F {devno} -c {capno} -p {bit}""")

    def infrasetup_instance(self, uridir: str) -> None:
        """ Handle infrastructure setup for this platform. """
        if self.instance_assigned_simulations():
            # This is a sim-host node.

            # copy sim infrastructure
            for slotno in range(len(self.parent_node.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno, uridir)
                self.extract_driver_tarball(slotno)

            if not self.parent_node.metasimulation_enabled:
                # load xdma driver
                self.load_xdma()
                self.load_xvsec()
                # flash fpgas
                self.flash_fpgas()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(len(self.parent_node.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def enumerate_fpgas(self, uridir: str) -> None:
        """ FPGAs are enumerated already with VCU118's """
        return

    def terminate_instance(self) -> None:
        """ XilinxVCU118InstanceDeployManager machines cannot be terminated. """
        return

    def start_sim_slot(self, slotno: int) -> None:
        """ start a simulation. (same as the default except that you have a mapping from slotno to a specific BDF)"""
        if self.instance_assigned_simulations():
            self.instance_logger(f"""Starting {self.sim_type_message} simulation for slot: {slotno}.""")
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = f"""{remote_home_dir}/sim_slot_{slotno}/"""
            assert slotno < len(self.parent_node.sim_slots), f"{slotno} can not index into sim_slots {len(self.parent_node.sim_slots)} on {self.parent_node.host}"
            server = self.parent_node.sim_slots[slotno]

            if not self.parent_node.metasimulation_enabled:
                self.instance_logger(f"""Determine BDF for {slotno}""")
                collect = run('lspci | grep -i xilinx')
                bdfs = [ i[:7] for i in collect.splitlines() if len(i.strip()) >= 0 ]
                bdf = bdfs[slotno].replace('.', ':').split(':')
                extra_args = f"+domain=0x0000 +bus=0x{bdf[0]} +device=0x{bdf[1]} +function=0x0 +bar=0x0 +pci-vendor=0x10ee +pci-device=0x903f"
            else:
                extra_args = None

            # make the local job results dir for this sim slot
            server.mkdir_and_prep_local_job_results_dir()
            sim_start_script_local_path = server.write_sim_start_script(slotno, (self.sim_command_requires_sudo() and has_sudo()), extra_args)
            put(sim_start_script_local_path, remote_sim_dir)

            with cd(remote_sim_dir):
                run("chmod +x sim-run.sh")
                run("./sim-run.sh")

