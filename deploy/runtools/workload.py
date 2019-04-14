""" Workload configuration information. """

import json
import os

class JobConfig:
    """ A single job that runs on a simulation.
    E.g. one spec benchmark, one of the risc-v tests, etc.

    This is created by feeding in all of the top-level values in the workload
    json + ONE of the workload elements.

    This essentially describes the local pieces that need to be fed to
    simulations and the remote outputs that need to be copied back. """

    filesystemsuffix = ".ext2"

    def __init__(self, singlejob_dict, parent_workload, index=0):
        self.parent_workload = parent_workload
        self.jobname = singlejob_dict.get("name", self.parent_workload.workload_name + str(index))
        # ignore files, command, we assume they are used only to build rootfses
        # eventually this functionality will be merged into the manager too
        joboutputs = singlejob_dict.get("outputs", [])
        self.outputs = joboutputs + parent_workload.common_outputs
        simoutputs = singlejob_dict.get("simulation_outputs", [])
        self.simoutputs = simoutputs + parent_workload.common_simulation_outputs

        if singlejob_dict.get("bootbinary") is not None:
            self.bootbinary = singlejob_dict.get("bootbinary")
        else:
            self.bootbinary = parent_workload.common_bootbinary

        if 'rootfs' in singlejob_dict:
            if singlejob_dict['rootfs'] is None:
                # Don't include a rootfs
                self.rootfs = None
            else:
                # Explicit per-job rootfs
                self.rootfs = parent_workload.workload_input_base_dir + singlejob_dict['rootfs']
        else:
            # No explicit per-job rootfs, inherit from workload
            if parent_workload.derive_rootfs:
                # No explicit workload rootfs, derive path from job name
                self.rootfs = self.parent_workload.workload_input_base_dir + self.jobname + self.filesystemsuffix
            elif parent_workload.common_rootfs is None:
                # Don't include a rootfs
                self.rootfs = None
            else:
                # Explicit rootfs path from workload
                self.rootfs = self.parent_workload.workload_input_base_dir + self.parent_workload.common_rootfs

    def bootbinary_path(self):
        return self.parent_workload.workload_input_base_dir + self.bootbinary

    def rootfs_path(self):
        return self.rootfs

    def __str__(self):
        return self.jobname

class WorkloadConfig:
    """ Parse the json file for a workload, produce a bunch of "jobs" objects
    that can be assigned to simulations.
    There are two types of workloads:
        1) # jobs = # of simulators, each one is explicitly specified
        2) there is one "job" - a binary/rootfs combo to be run on all sims
    """

    workloadinputs = 'workloads/'
    workloadoutputs = 'results-workloads/'

    def __init__(self, workloadfilename, launch_time):
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

        # rootfses, bootbinaries live here
        self.workload_input_base_dir = self.workloadinputs + self.workload_name + '/'
        self.uniform_mode = workloadjson.get("workloads") is None
        if not self.uniform_mode:
            self.jobs = [JobConfig(job, self) for job in workloadjson.get("workloads")]

        self.post_run_hook = workloadjson.get("post_run_hook")

        # we set this up as an absolute path to simplify later use
        self.job_results_dir = """{}/results-workload/{}-{}/""".format(
                                                            os.getcwd(),
                                                            launch_time,
                                                            self.workload_name)

        #import code
        #code.interact(local=locals())

    def get_job(self, index):
        if not self.uniform_mode:
            return self.jobs[index]
        else:
            return JobConfig(dict(), self, index)

    def are_all_jobs_assigned(self, numjobsassigned):
        """ Return True if each job is assigned to at least one simulation.
        In the uniform case, always return True """
        if not self.uniform_mode:
            return numjobsassigned == len(self.jobs)
        return True



