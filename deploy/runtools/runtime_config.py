""" This file manages the overall configuration of the system for running
simulation tasks. """

from __future__ import print_function

from time import strftime, gmtime
import ConfigParser
import pprint
import logging

from fabric.api import *
from awstools.awstools import *
from awstools.afitools import *
from runtools.firesim_topology_with_passes import FireSimTopologyWithPasses
from runtools.workload import WorkloadConfig
from runtools.run_farm import RunFarm
from runtools.runtime_hw_config import *
from util.streamlogger import StreamLogger
import os

LOCAL_SYSROOT_LIB = "../sim/lib-install/lib/"
CUSTOM_RUNTIMECONFS_BASE = "../sim/custom-runtime-configs/"

rootLogger = logging.getLogger()

class InnerRuntimeConfiguration:
    """ Pythonic version of config_runtime.ini """

    def __init__(self, runtimeconfigfile, configoverridedata):
        runtime_configfile = ConfigParser.ConfigParser(allow_no_value=True)
        runtime_configfile.read(runtimeconfigfile)
        runtime_dict = {s:dict(runtime_configfile.items(s)) for s in runtime_configfile.sections()}

        # override parts of the runtime conf if specified
        configoverrideval = configoverridedata
        if configoverrideval != "":
            ## handle overriding part of the runtime conf
            configoverrideval = configoverrideval.split()
            overridesection = configoverrideval[0]
            overridefield = configoverrideval[1]
            overridevalue = configoverrideval[2]
            rootLogger.warning("Overriding part of the runtime config with: ")
            rootLogger.warning("""[{}]""".format(overridesection))
            rootLogger.warning(overridefield + "=" + overridevalue)
            runtime_dict[overridesection][overridefield] = overridevalue

        runfarmtagprefix = "" if 'FIRESIM_RUNFARM_PREFIX' not in os.environ else os.environ['FIRESIM_RUNFARM_PREFIX']
        if runfarmtagprefix != "":
            runfarmtagprefix += "-"

        self.runfarmtag = runfarmtagprefix + runtime_dict['runfarm']['runfarmtag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.runfarmtag = aws_resource_names_dict['runfarmprefix'] + "-" + self.runfarmtag

        self.f1_16xlarges_requested = int(runtime_dict['runfarm']['f1_16xlarges']) if 'f1_16xlarges' in runtime_dict['runfarm'] else 0
        self.f1_4xlarges_requested = int(runtime_dict['runfarm']['f1_4xlarges']) if 'f1_4xlarges' in runtime_dict['runfarm'] else 0
        self.m4_16xlarges_requested = int(runtime_dict['runfarm']['m4_16xlarges']) if 'm4_16xlarges' in runtime_dict['runfarm'] else 0
        self.f1_2xlarges_requested = int(runtime_dict['runfarm']['f1_2xlarges']) if 'f1_2xlarges' in runtime_dict['runfarm'] else 0

        self.run_instance_market = runtime_dict['runfarm']['runinstancemarket']
        self.spot_interruption_behavior = runtime_dict['runfarm']['spotinterruptionbehavior']
        self.spot_max_price = runtime_dict['runfarm']['spotmaxprice']

        self.topology = runtime_dict['targetconfig']['topology']
        self.no_net_num_nodes = int(runtime_dict['targetconfig']['no_net_num_nodes'])
        self.linklatency = int(runtime_dict['targetconfig']['linklatency'])
        self.switchinglatency = int(runtime_dict['targetconfig']['switchinglatency'])
        self.netbandwidth = int(runtime_dict['targetconfig']['netbandwidth'])
        self.profileinterval = int(runtime_dict['targetconfig']['profileinterval'])
        # Default values
        self.trace_enable = False
        self.trace_select = "0"
        self.trace_start = "0"
        self.trace_end = "-1"
        self.trace_output_format = "0"
        self.autocounter_readrate = 0
        self.zerooutdram = False
        self.disable_asserts = False
        self.print_start = "0"
        self.print_end = "-1"
        self.print_cycle_prefix = True

        if 'tracing' in runtime_dict:
            self.trace_enable = runtime_dict['tracing'].get('enable') == "yes"
            self.trace_select = runtime_dict['tracing'].get('selector', "0")
            self.trace_start = runtime_dict['tracing'].get('start', "0")
            self.trace_end = runtime_dict['tracing'].get('end', "-1")
            self.trace_output_format = runtime_dict['tracing'].get('output_format', "0")
        if 'autocounter' in runtime_dict:
            self.autocounter_readrate = int(runtime_dict['autocounter'].get('readrate', "0"))
        self.defaulthwconfig = runtime_dict['targetconfig']['defaulthwconfig']
        if 'hostdebug' in runtime_dict:
            self.zerooutdram = runtime_dict['hostdebug'].get('zerooutdram') == "yes"
            self.disable_asserts = runtime_dict['hostdebug'].get('disable_synth_asserts') == "yes"
        if 'synthprint' in runtime_dict:
            self.print_start = runtime_dict['synthprint'].get("start", "0")
            self.print_end = runtime_dict['synthprint'].get("end", "-1")
            self.print_cycle_prefix = runtime_dict['synthprint'].get("cycleprefix", "yes") == "yes"

        self.workload_name = runtime_dict['workload']['workloadname']
        # an extra tag to differentiate workloads with the same name in results names
        self.suffixtag = runtime_dict['workload']['suffixtag'] if 'suffixtag' in runtime_dict['workload'] else ""
        self.terminateoncompletion = runtime_dict['workload']['terminateoncompletion'] == "yes"

    def __str__(self):
        return pprint.pformat(vars(self))

class RuntimeConfig:
    """ This class manages the overall configuration of the manager for running
    simulation tasks. """

    def __init__(self, args):
        """ This reads runtime configuration files, massages them into formats that
        the rest of the manager expects, and keeps track of other info. """
        self.launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        # construct pythonic db of hardware configurations available to us at
        # runtime.
        self.runtimehwdb = RuntimeHWDB(args.hwdbconfigfile)
        rootLogger.debug(self.runtimehwdb)

        self.innerconf = InnerRuntimeConfiguration(args.runtimeconfigfile,
                                                   args.overrideconfigdata)
        rootLogger.debug(self.innerconf)

        # construct a privateip -> instance obj mapping for later use
        #self.instancelookuptable = instance_privateip_lookup_table(
        #    f1_16_instances + f1_2_instances + m4_16_instances)

        # setup workload config obj, aka a list of workloads that can be assigned
        # to a server
        self.workload = WorkloadConfig(self.innerconf.workload_name, self.launch_time,
                                       self.innerconf.suffixtag)

        self.runfarm = RunFarm(self.innerconf.f1_16xlarges_requested,
                               self.innerconf.f1_4xlarges_requested,
                               self.innerconf.f1_2xlarges_requested,
                               self.innerconf.m4_16xlarges_requested,
                               self.innerconf.runfarmtag,
                               self.innerconf.run_instance_market,
                               self.innerconf.spot_interruption_behavior,
                               self.innerconf.spot_max_price)

        # start constructing the target configuration tree
        self.firesim_topology_with_passes = FireSimTopologyWithPasses(
            self.innerconf.topology, self.innerconf.no_net_num_nodes,
            self.runfarm, self.runtimehwdb, self.innerconf.defaulthwconfig,
            self.workload, self.innerconf.linklatency,
            self.innerconf.switchinglatency, self.innerconf.netbandwidth,
            self.innerconf.profileinterval, self.innerconf.trace_enable,
            self.innerconf.trace_select, self.innerconf.trace_start, self.innerconf.trace_end,
            self.innerconf.trace_output_format,
            self.innerconf.autocounter_readrate, self.innerconf.terminateoncompletion,
            self.innerconf.zerooutdram, self.innerconf.disable_asserts,
            self.innerconf.print_start, self.innerconf.print_end,
            self.innerconf.print_cycle_prefix)

    def launch_run_farm(self):
        """ directly called by top-level launchrunfarm command. """
        self.runfarm.launch_run_farm()

    def terminate_run_farm(self, terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                           terminatesomem4_16, forceterminate):
        """ directly called by top-level terminaterunfarm command. """
        self.runfarm.terminate_run_farm(terminatesomef1_16, terminatesomef1_4, terminatesomef1_2,
                                        terminatesomem4_16, forceterminate)

    def infrasetup(self):
        """ directly called by top-level infrasetup command. """
        # set this to True if you want to use mock boto3 instances for testing
        # the manager.
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.infrasetup_passes(use_mock_instances_for_testing)

    def boot(self):
        """ directly called by top-level boot command. """
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.boot_simulation_passes(use_mock_instances_for_testing)

    def kill(self):
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.kill_simulation_passes(use_mock_instances_for_testing)

    def run_workload(self):
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.run_workload_passes(use_mock_instances_for_testing)



