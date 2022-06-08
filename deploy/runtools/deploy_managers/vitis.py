""" Run Farm management. """

from __future__ import annotations

import re
import logging
import abc
from fabric.api import prefix, local, run, env, cd, warn_only, put, settings, hide # type: ignore
from fabric.contrib.project import rsync_project # type: ignore
from os.path import join as pjoin

from util.streamlogger import StreamLogger
from runtools.run_farm_deploy_managers import InstanceDeployManager

from typing import List, Dict, TYPE_CHECKING
if TYPE_CHECKING:
    from runtools.run_farm import Inst

rootLogger = logging.getLogger()

class VitisInstanceDeployManager(InstanceDeployManager):
    """ This class manages a Vitis-enabled instance """
    def __init__(self, parent_node: Inst) -> None:
        super().__init__(parent_node)

    def instance_logger(self, logstr: str) -> None:
        rootLogger.info("""[{}] """.format(env.host_string) + logstr)

    def clear_fpgas(self) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Clearing all FPGA Slots.""")

            card_bdfs = []
            with settings(warn_only=True), hide('everything'):
                collect = run('xbutil examine')
                for line in collect.splitlines():
                    line_stripped = line.strip()
                    match = re.search('\[(.*)\]', line_stripped)
                    if match:
                        card_bdfs.append(match.group(1))

            for card_bdf in card_bdfs:
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    run("xbutil validate --device {} --run quick".format(card_bdf))

    def get_simulation_dir(self):
        remote_home_dir = ""
        if self.parentnode.override_simulation_dir:
            remote_home_dir = self.parentnode.override_simulation_dir
        else:
            with StreamLogger('stdout'), StreamLogger('stderr'):
                remote_home_dir = run('echo $HOME')

        return remote_home_dir

    def copy_sim_slot_infrastructure(self, slotno):
        """ copy all the simulation infrastructure to the remote node. """
        if self.instance_assigned_simulations():
            assert slotno < len(self.parent_node.sim_slots)
            serv = self.parent_node.sim_slots[slotno]

            self.instance_logger("""Copying FPGA simulation infrastructure for slot: {}.""".format(slotno))

            remote_home_dir = self.parent_node.get_sim_dir()

            remote_sim_dir = """{}/sim_slot_{}/""".format(remote_home_dir, slotno)
            remote_sim_rsync_dir = remote_sim_dir + "rsyncdir/"
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""mkdir -p {}""".format(remote_sim_rsync_dir))

            files_to_copy = serv.get_required_files_local_paths()
            for local_path, remote_path in files_to_copy:
                # here, filename is a pair of (local path, remote path)
                with StreamLogger('stdout'), StreamLogger('stderr'):
                    # -z --inplace
                    rsync_cap = rsync_project(local_dir=local_path, remote_dir=pjoin(remote_sim_rsync_dir, remote_path),
                                ssh_opts="-o StrictHostKeyChecking=no", extra_opts="-L", capture=True)
                    rootLogger.debug(rsync_cap)
                    rootLogger.debug(rsync_cap.stderr)

            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("""cp -r {}/* {}/""".format(remote_sim_rsync_dir, remote_sim_dir), shell=True)

    def copy_switch_slot_infrastructure(self, switchslot):
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
                    put(filename, pjoin(remote_switch_dir, remote_path), mirror_local_mode=True)

    def start_sim_slot(self, slotno: int) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Starting FPGA simulation for slot: {}.""".format(slotno))
            remote_home_dir = self.parent_node.sim_dir
            remote_sim_dir = """{}/sim_slot_{}/""".format(remote_home_dir, slotno)
            assert slotno < len(self.parent_node.sim_slots)
            server = self.parent_node.sim_slots[slotno]
            with cd(remote_sim_dir), StreamLogger('stdout'), StreamLogger('stderr'):
                server.run_sim_start_command(slotno)

    def kill_switch_slot(self, switchslot: int) -> None:
        """ kill the switch in slot switchslot. """
        if self.instance_assigned_switches():
            self.instance_logger("""Killing switch simulation for switchslot: {}.""".format(switchslot))
            assert switchslot < len(self.parent_node.switch_slots)
            switch = self.parent_node.switch_slots[switchslot]
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                run(switch.get_switch_kill_command())

    def kill_sim_slot(self, slotno: int) -> None:
        if self.instance_assigned_simulations():
            self.instance_logger("""Killing FPGA simulation for slot: {}.""".format(slotno))
            assert slotno < len(self.parent_node.sim_slots)
            server = self.parent_node.sim_slots[slotno]
            with warn_only(), StreamLogger('stdout'), StreamLogger('stderr'):
                run(server.get_sim_kill_command(slotno))

    def instance_assigned_simulations(self) -> bool:
        """ return true if this instance has any assigned fpga simulations. """
        return len(self.parent_node.sim_slots) != 0

    def instance_assigned_switches(self) -> bool:
        """ return true if this instance has any assigned switch simulations. """
        return len(self.parent_node.switch_slots) != 0

    def infrasetup_instance(self):
        """ Handle infrastructure setup for this instance. """
        # check if fpga node
        if self.instance_assigned_simulations():
            # This is an FPGA-host node.

            # copy fpga sim infrastructure
            for slotno in range(len(self.parentnode.sim_slots)):
                self.copy_sim_slot_infrastructure(slotno)

            self.clear_fpgas()

        if self.instance_assigned_switches():
            # all nodes could have a switch
            for slotno in range(len(self.parentnode.switch_slots)):
                self.copy_switch_slot_infrastructure(slotno)

    def start_switches_instance(self):
        """ Boot up all the switches in a screen. """
        # TODO: figure out how to remove sudo
        # remove shared mem pages used by switches
        if self.instance_assigned_switches():
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

            for slotno in range(len(self.parent_node.switch_slots)):
                self.start_switch_slot(slotno)

    def start_simulations_instance(self):
        """ Boot up all the sims in a screen. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(len(self.parentnode.sim_slots)):
                self.start_sim_slot(slotno)

    def kill_switches_instance(self):
        """ Kill all the switches on this instance. """
        # TODO: figure out how to remove sudo
        if self.instance_assigned_switches():
            for slotno in range(len(self.parent_node.switch_slots)):
                self.kill_switch_slot(slotno)
            with StreamLogger('stdout'), StreamLogger('stderr'):
                run("sudo rm -rf /dev/shm/*")

    def kill_simulations_instance(self, disconnect_all_nbds=True):
        """ Kill all simulations on this instance. """
        if self.instance_assigned_simulations():
            # only on sim nodes
            for slotno in range(len(self.parent_node.sim_slots)):
                self.kill_sim_slot(slotno)

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
                for counter in range(len(self.parent_node.switch_slots)):
                    switchsim = self.parent_node.switch_slots[counter]
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

                # TODO: How to re-enable
                #if terminateoncompletion:
                #    # terminate the instance since teardown is called and instance
                #    # termination is enabled
                #    assert isinstance(self.boto3_instance_object, EC2InstanceResource)
                #    instanceids = get_instance_ids_for_instances([self.boto3_instance_object])
                #    terminate_instances(instanceids, dryrun=False)

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
            # this node has fpga sims attached

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

                for counter, switchsim in enumerate(self.parent_node.switch_slots):
                    switchsim.copy_back_switchlog_from_run(job_results_dir, counter)

            # TODO: How to do this?
            #if now_done and terminateoncompletion:
            #    # terminate the instance since everything is done and instance
            #    # termination is enabled
            #    assert isinstance(self.boto3_instance_object, EC2InstanceResource)
            #    instanceids = get_instance_ids_for_instances([self.boto3_instance_object])
            #    terminate_instances(instanceids, dryrun=False)

            return {'switches': switchescompleteddict, 'sims': jobs_done_q}

        assert False
