""" Workload configuration information. """

from __future__ import annotations

import json
import os

from typing import List, Optional, Dict, Any, Tuple

class JobConfig:
    """ A single job that runs on a simulation.
    E.g. one spec benchmark, one of the risc-v tests, etc.

    This is created by feeding in all of the top-level values in the workload
    json + ONE of the workload elements.

    This essentially describes the local pieces that need to be fed to
    simulations and the remote outputs that need to be copied back. """

    filesystemsuffix: str = ".ext2"
    parent_workload: WorkloadConfig
    jobname: str
    outputs: List[str]
    simoutputs: List[str]
    siminputs: List[str]
    bootbinary: str
    rootfs: Optional[str]

    def __init__(self, singlejob_dict: Dict[str, Any], parent_workload: WorkloadConfig, index: int = 0) -> None:
        self.parent_workload = parent_workload
        self.jobname = singlejob_dict.get("name", self.parent_workload.workload_name + str(index))
        # ignore files, command, we assume they are used only to build rootfses
        # eventually this functionality will be merged into the manager too
        joboutputs = singlejob_dict.get("outputs", [])
        self.outputs = joboutputs + self.parent_workload.common_outputs
        simoutputs = singlejob_dict.get("simulation_outputs", [])
        self.simoutputs = simoutputs + self.parent_workload.common_simulation_outputs
        siminputs = singlejob_dict.get("simulation_inputs", [])
        self.siminputs = siminputs + self.parent_workload.common_simulation_inputs

        if singlejob_dict.get("bootbinary") is not None:
            self.bootbinary = singlejob_dict["bootbinary"]
        else:
            self.bootbinary = self.parent_workload.common_bootbinary

        if 'rootfs' in singlejob_dict:
            if singlejob_dict['rootfs'] is None:
                # Don't include a rootfs
                self.rootfs = None
            else:
                # Explicit per-job rootfs
                self.rootfs = self.parent_workload.workload_input_base_dir + singlejob_dict['rootfs']
        else:
            # No explicit per-job rootfs, inherit from workload
            if self.parent_workload.derive_rootfs:
                # No explicit workload rootfs, derive path from job name
                self.rootfs = self.parent_workload.workload_input_base_dir + self.jobname + self.filesystemsuffix
            elif self.parent_workload.common_rootfs is None:
                # Don't include a rootfs
                self.rootfs = None
            else:
                # Explicit rootfs path from workload
                self.rootfs = self.parent_workload.workload_input_base_dir + self.parent_workload.common_rootfs

    def bootbinary_path(self) -> str:
        return self.parent_workload.workload_input_base_dir + self.bootbinary

    def get_siminputs(self) -> List[Tuple[str, str]]:
        # remote filename for a siminput gets prefixed with the job's name
        return list(map(lambda x: (self.parent_workload.workload_input_base_dir + "/" + x, self.jobname + "-" + x), self.siminputs))

    def rootfs_path(self) -> Optional[str]:
        return self.rootfs

    def __str__(self) -> str:
        return self.jobname

class WorkloadConfig:
    """ Parse the json file for a workload, produce a bunch of "jobs" objects
    that can be assigned to simulations.
    There are two types of workloads:
        1) # jobs = # of simulators, each one is explicitly specified
        2) there is one "job" - a binary/rootfs combo to be run on all sims
    """

    workloadinputs: str = 'workloads/'
    workloadoutputs: str = 'results-workloads/'
    workloadfilename: str
    common_rootfs: Optional[str]
    derive_rootfs: bool
    common_bootbinary: str
    workload_name: str
    common_outputs: str
    common_simulation_outputs: List[str]
    common_simulation_inputs: List[str]
    workload_input_base_dir: str
    uniform_mode: bool
    jobs: List[JobConfig]
    post_run_hook: str
    job_results_dir: str
    job_monitoring_dir: str

    def __init__(self, workloadfilename: str, launch_time: str, suffixtag: str) -> None:
        self.workloadfilename = self.workloadinputs + workloadfilename
        workloadjson = None
        with open(self.workloadfilename) as json_data:
            workloadjson = json.load(json_data)

        if 'common_rootfs' in workloadjson:
            self.common_rootfs = workloadjson["common_rootfs"]
            self.derive_rootfs = False
        else:
            self.common_rootfs = None
            self.derive_rootfs = True

        self.common_bootbinary = workloadjson.get("common_bootbinary")
        self.workload_name = workloadjson.get("benchmark_name")
        #self.rootfs_base = workloadjson.get("deliver_dir")
        # currently ignore: overlay_dir, common_args, common_files
        # we assume they are only used to build rootfses. eventually this
        # functionality will be merged into the manager too
        self.common_outputs = workloadjson.get("common_outputs", [])
        self.common_simulation_outputs = workloadjson.get("common_simulation_outputs", [])
        self.common_simulation_inputs = workloadjson.get("common_simulation_inputs", [])

        # rootfses, bootbinaries live here
        self.workload_input_base_dir = self.workloadinputs + self.workload_name + '/'
        self.uniform_mode = workloadjson.get("workloads") is None
        if not self.uniform_mode:
            self.jobs = [JobConfig(job, self) for job in workloadjson.get("workloads")]

        self.post_run_hook = workloadjson.get("post_run_hook")

        appendsuffix = ""
        if suffixtag:
            appendsuffix = "-" + suffixtag

        # we set this up as an absolute path to simplify later use
        self.job_results_dir = """{}/results-workload/{}-{}{}/""".format(
                                                            os.getcwd(),
                                                            launch_time,
                                                            self.workload_name,
                                                            appendsuffix)
        # hidden dir to keep job monitoring information
        self.job_monitoring_dir = self.job_results_dir + ".monitoring-dir/"

        #import code
        #code.interact(local=locals())

    def get_job(self, index: int) -> JobConfig:
        if not self.uniform_mode:
            return self.jobs[index]
        else:
            return JobConfig(dict(), self, index)

    def are_all_jobs_assigned(self, numjobsassigned: int) -> bool:
        """ Return True if each job is assigned to at least one simulation.
        In the uniform case, always return True """
        if not self.uniform_mode:
            return numjobsassigned == len(self.jobs)
        return True



