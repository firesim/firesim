""" This file manages the overall configuration of the system for running
simulation tasks. """

from __future__ import print_function

import argparse
from datetime import timedelta
from time import strftime, gmtime
import pprint
import logging
import yaml
import os
import sys
from fabric.api import prefix # type: ignore

from awstools.awstools import *
from awstools.afitools import *
from runtools.firesim_topology_with_passes import FireSimTopologyWithPasses
from runtools.workload import WorkloadConfig
from runtools.run_farm import RunFarm
from util.streamlogger import StreamLogger
from runtools.utils import MacAddress

from typing import Optional, Dict, Any, List, Sequence

LOCAL_DRIVERS_BASE = "../sim/output/f1/"
CUSTOM_RUNTIMECONFS_BASE = "../sim/custom-runtime-configs/"

rootLogger = logging.getLogger()

class RuntimeHWConfig:
    """ A pythonic version of the entires in config_hwdb.yaml """
    name: str
    platform: str
    agfi: str
    deploytriplet: Optional[str]
    customruntimeconfig: str
    driver_built: bool

    def __init__(self, name: str, hwconfig_dict: Dict[str, Any]) -> None:
        self.name = name
        self.agfi = hwconfig_dict['agfi']
        self.deploytriplet = hwconfig_dict['deploy_triplet_override']
        if self.deploytriplet is not None:
            rootLogger.warning("{} is overriding a deploy triplet in your config_hwdb.yaml file.  Make sure you understand why!".format(name))
        self.customruntimeconfig = hwconfig_dict['custom_runtime_config']
        # note whether we've built a copy of the simulation driver for this hwconf
        self.driver_built = False

    def get_deploytriplet_for_config(self) -> str:
        """ Get the deploytriplet for this configuration. This memoizes the request
        to the AWS AGFI API."""
        if self.deploytriplet is not None:
            return self.deploytriplet
        rootLogger.debug("Setting deploytriplet by querying the AGFI's description.")
        self.deploytriplet = get_firesim_tagval_for_agfi(self.agfi,
                                                         'firesim-deploytriplet')
        return self.deploytriplet

    def get_design_name(self) -> str:
        """ Returns the name used to prefix MIDAS-emitted files. (The DESIGN make var) """
        my_deploytriplet = self.get_deploytriplet_for_config()
        my_design = my_deploytriplet.split("-")[0]
        return my_design

    def get_local_driver_binaryname(self) -> str:
        """ Get the name of the driver binary. """
        return self.get_design_name() + "-f1"

    def get_local_driver_path(self) -> str:
        """ return relative local path of the driver used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + my_deploytriplet + "/"
        fpga_driver_local = drivers_software_base + self.get_local_driver_binaryname()
        return fpga_driver_local

    def get_local_runtimeconf_binaryname(self) -> str:
        """ Get the name of the runtimeconf file. """
        return "runtime.conf" if self.customruntimeconfig is None else os.path.basename(self.customruntimeconfig)

    def get_local_runtime_conf_path(self) -> str:
        """ return relative local path of the runtime conf used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + my_deploytriplet + "/"
        my_runtimeconfig = self.customruntimeconfig
        if my_runtimeconfig is None:
            runtime_conf_local = drivers_software_base + "runtime.conf"
        else:
            runtime_conf_local = CUSTOM_RUNTIMECONFS_BASE + my_runtimeconfig
        return runtime_conf_local

    def get_boot_simulation_command(self, slotid: int, all_macs: Sequence[Optional[MacAddress]],
            all_rootfses: Sequence[Optional[str]], all_linklatencies: Sequence[Optional[int]],
            all_netbws: Sequence[Optional[int]], profile_interval: int,
            all_bootbinaries: List[str], trace_enable: bool,
            trace_select: str, trace_start: str, trace_end: str,
            trace_output_format: str,
            autocounter_readrate: int, all_shmemportnames: List[str],
            enable_zerooutdram: bool, disable_asserts_arg: bool,
            print_start: str, print_end: str,
            enable_print_cycle_prefix: bool) -> str:
        """ return the command used to boot the simulation. this has to have
        some external params passed to it, because not everything is contained
        in a runtimehwconfig. TODO: maybe runtimehwconfig should be renamed to
        pre-built runtime config? It kinda contains a mix of pre-built and
        runtime parameters currently. """

        # TODO: supernode support
        tracefile = "+tracefile=TRACEFILE" if trace_enable else ""
        autocounterfile = "+autocounter-filename-base=AUTOCOUNTERFILE"

        # this monstrosity boots the simulator, inside screen, inside script
        # the sed is in there to get rid of newlines in runtime confs
        driver = self.get_local_driver_binaryname()
        runtimeconf = self.get_local_runtimeconf_binaryname()

        def array_to_plusargs(valuesarr, plusarg):
            args = []
            for index, arg in enumerate(valuesarr):
                if arg is not None:
                    args.append("""{}{}={}""".format(plusarg, index, arg))
            return " ".join(args) + " "

        def array_to_lognames(values, prefix):
            names = ["{}{}".format(prefix, i) if val is not None else None
                     for (i, val) in enumerate(values)]
            return array_to_plusargs(names, "+" + prefix)

        command_macs = array_to_plusargs(all_macs, "+macaddr")
        command_rootfses = array_to_plusargs(all_rootfses, "+blkdev")
        command_linklatencies = array_to_plusargs(all_linklatencies, "+linklatency")
        command_netbws = array_to_plusargs(all_netbws, "+netbw")
        command_shmemportnames = array_to_plusargs(all_shmemportnames, "+shmemportname")
        command_dromajo = "+drj_dtb=" + all_bootbinaries[0] + ".dtb" +  " +drj_bin=" + all_bootbinaries[0] + " +drj_rom=" + all_bootbinaries[0] + ".rom"

        command_niclogs = array_to_lognames(all_macs, "niclog")
        command_blkdev_logs = array_to_lognames(all_rootfses, "blkdev-log")

        command_bootbinaries = array_to_plusargs(all_bootbinaries, "+prog")
        zero_out_dram = "+zero-out-dram" if (enable_zerooutdram) else ""
        disable_asserts = "+disable-asserts" if (disable_asserts_arg) else ""
        print_cycle_prefix = "+print-no-cycle-prefix" if not enable_print_cycle_prefix else ""

        # TODO supernode support
        dwarf_file_name = "+dwarf-file-name=" + all_bootbinaries[0] + "-dwarf"

        # TODO: supernode support (tracefile, trace-select.. etc)
        basecommand = f"""screen -S fsim{slotid} -d -m bash -c "script -f -c 'stty intr ^] && sudo ./{driver} +permissive $(sed \':a;N;$!ba;s/\\n/ /g\' {runtimeconf}) +slotid={slotid} +profile-interval={profile_interval} {zero_out_dram} {disable_asserts} {command_macs} {command_rootfses} {command_niclogs} {command_blkdev_logs}  {tracefile} +trace-select={trace_select} +trace-start={trace_start} +trace-end={trace_end} +trace-output-format={trace_output_format} {dwarf_file_name} +autocounter-readrate={autocounter_readrate} {autocounterfile} {command_dromajo} {print_cycle_prefix} +print-start={print_start} +print-end={print_end} {command_linklatencies} {command_netbws}  {command_shmemportnames} +permissive-off {command_bootbinaries} && stty intr ^c' uartlog"; sleep 1"""

        return basecommand



    def get_kill_simulation_command(self) -> str:
        driver = self.get_local_driver_binaryname()
        # Note that pkill only works for names <=15 characters
        return """sudo pkill -SIGKILL {driver}""".format(driver=driver[:15])


    def build_fpga_driver(self) -> None:
        """ Build FPGA driver for running simulation """
        if self.driver_built:
            # we already built the driver at some point
            return
        # TODO there is a duplicate of this in runtools
        triplet_pieces = self.get_deploytriplet_for_config().split("-")
        design = triplet_pieces[0]
        target_config = triplet_pieces[1]
        platform_config = triplet_pieces[2]
        rootLogger.info("Building FPGA software driver for " + str(self.get_deploytriplet_for_config()))
        with prefix('cd ../'), \
             prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
             prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
             prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
             prefix('source ./sourceme-f1-manager.sh'), \
             prefix('cd sim/'), \
             StreamLogger('stdout'), \
             StreamLogger('stderr'):
            localcap = None
            with settings(warn_only=True):
                driverbuildcommand = """make DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} f1""".format(design, target_config, platform_config)
                localcap = local(driverbuildcommand, capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))
            if localcap.failed:
                rootLogger.info("FPGA software driver build failed. Exiting. See log for details.")
                rootLogger.info("""You can also re-run '{}' in the 'firesim/sim' directory to debug this error.""".format(driverbuildcommand))
                sys.exit(1)

        self.driver_built = True


    def __str__(self) -> str:
        return """RuntimeHWConfig: {}\nDeployTriplet: {}\nAGFI: {}\nCustomRuntimeConf: {}""".format(self.name, self.deploytriplet, self.agfi, str(self.customruntimeconfig))


class RuntimeHWDB:
    """ This class manages the hardware configurations that are available
    as endpoints on the simulation. """
    hwconf_dict: Dict[str, RuntimeHWConfig]

    def __init__(self, hardwaredbconfigfile: str) -> None:

        agfidb_configfile = None
        with open(hardwaredbconfigfile, "r") as yaml_file:
            agfidb_configfile = yaml.safe_load(yaml_file)

        agfidb_dict = agfidb_configfile

        self.hwconf_dict = {s: RuntimeHWConfig(s, v) for s, v in agfidb_dict.items()}

    def get_runtimehwconfig_from_name(self, name: str) -> RuntimeHWConfig:
        return self.hwconf_dict[name]

    def __str__(self) -> str:
        return pprint.pformat(vars(self))


class InnerRuntimeConfiguration:
    """ Pythonic version of config_runtime.yaml """
    runfarmtag: str
    f1_16xlarges_requested: int
    f1_4xlarges_requested: int
    m4_16xlarges_requested: int
    f1_2xlarges_requested: int
    run_instance_market: str
    spot_interruption_behavior: str
    spot_max_price: str
    topology: str
    no_net_num_nodes: int
    linklatency: int
    switchinglatency: int
    netbandwidth: int
    profileinterval: int
    launch_timeout: timedelta
    always_expand: bool
    trace_enable: bool
    trace_select: str
    trace_start: str
    trace_end: str
    trace_output_format: str
    autocounter_readrate: int
    zerooutdram: bool
    disable_asserts: bool
    print_start: str
    print_end: str
    print_cycle_prefix: bool
    workload_name: str
    suffixtag: str
    terminateoncompletion: bool

    def __init__(self, runtimeconfigfile: str, configoverridedata: str) -> None:

        runtime_dict = None
        with open(runtimeconfigfile, "r") as yaml_file:
            runtime_dict = yaml.safe_load(yaml_file)

        # override parts of the runtime conf if specified
        if configoverridedata != "":
            ## handle overriding part of the runtime conf
            configoverrideval = configoverridedata.split()
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

        self.runfarmtag = runfarmtagprefix + runtime_dict['run_farm']['run_farm_tag']

        aws_resource_names_dict = aws_resource_names()
        if aws_resource_names_dict['runfarmprefix'] is not None:
            # if specified, further prefix runfarmtag
            self.runfarmtag = aws_resource_names_dict['runfarmprefix'] + "-" + self.runfarmtag

        self.f1_16xlarges_requested = int(runtime_dict['run_farm']['f1_16xlarges']) if 'f1_16xlarges' in runtime_dict['run_farm'] else 0
        self.f1_4xlarges_requested = int(runtime_dict['run_farm']['f1_4xlarges']) if 'f1_4xlarges' in runtime_dict['run_farm'] else 0
        self.m4_16xlarges_requested = int(runtime_dict['run_farm']['m4_16xlarges']) if 'm4_16xlarges' in runtime_dict['run_farm'] else 0
        self.f1_2xlarges_requested = int(runtime_dict['run_farm']['f1_2xlarges']) if 'f1_2xlarges' in runtime_dict['run_farm'] else 0

        self.run_instance_market = runtime_dict['run_farm']['run_instance_market']
        self.spot_interruption_behavior = runtime_dict['run_farm']['spot_interruption_behavior']
        self.spot_max_price = runtime_dict['run_farm']['spot_max_price']

        self.topology = runtime_dict['target_config']['topology']
        self.no_net_num_nodes = int(runtime_dict['target_config']['no_net_num_nodes'])
        self.linklatency = int(runtime_dict['target_config']['link_latency'])
        self.switchinglatency = int(runtime_dict['target_config']['switching_latency'])
        self.netbandwidth = int(runtime_dict['target_config']['net_bandwidth'])
        self.profileinterval = int(runtime_dict['target_config']['profile_interval'])

        if 'launch_instances_timeout_minutes' in runtime_dict['run_farm']:
            self.launch_timeout = timedelta(minutes=int(runtime_dict['run_farm']['launch_instances_timeout_minutes']))
        else:
            self.launch_timeout = timedelta() # default to legacy behavior of not waiting

        self.always_expand = runtime_dict['run_farm'].get('always_expand_runfarm', "yes") == "yes"

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
            self.autocounter_readrate = int(runtime_dict['autocounter'].get('read_rate', "0"))
        self.defaulthwconfig = runtime_dict['target_config']['default_hw_config']
        if 'host_debug' in runtime_dict:
            self.zerooutdram = runtime_dict['host_debug'].get('zero_out_dram') == "yes"
            self.disable_asserts = runtime_dict['host_debug'].get('disable_synth_asserts') == "yes"
        if 'synth_print' in runtime_dict:
            self.print_start = runtime_dict['synth_print'].get("start", "0")
            self.print_end = runtime_dict['synth_print'].get("end", "-1")
            self.print_cycle_prefix = runtime_dict['synth_print'].get("cycle_prefix", "yes") == "yes"

        self.workload_name = runtime_dict['workload']['workload_name']
        # an extra tag to differentiate workloads with the same name in results names
        self.suffixtag = runtime_dict['workload']['suffix_tag'] if 'suffix_tag' in runtime_dict['workload'] else None
        self.terminateoncompletion = runtime_dict['workload']['terminate_on_completion'] == "yes"

    def __str__(self) -> str:
        return pprint.pformat(vars(self))

class RuntimeConfig:
    """ This class manages the overall configuration of the manager for running
    simulation tasks. """

    def __init__(self, args: argparse.Namespace) -> None:
        """ This reads runtime configuration files, massages them into formats that
        the rest of the manager expects, and keeps track of other info. """
        self.launch_time = strftime("%Y-%m-%d--%H-%M-%S", gmtime())

        self.args = args

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
                               self.innerconf.spot_max_price,
                               self.innerconf.launch_timeout,
                               self.innerconf.always_expand)

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

    def launch_run_farm(self) -> None:
        """ directly called by top-level launchrunfarm command. """
        self.runfarm.launch_run_farm()

    def terminate_run_farm(self) -> None:
        """ directly called by top-level terminaterunfarm command. """
        args = self.args
        self.runfarm.terminate_run_farm(args.terminatesomef116, args.terminatesomef14, args.terminatesomef12,
                                        args.terminatesomem416, args.forceterminate)

    def infrasetup(self) -> None:
        """ directly called by top-level infrasetup command. """
        # set this to True if you want to use mock boto3 instances for testing
        # the manager.
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.infrasetup_passes(use_mock_instances_for_testing)

    def boot(self) -> None:
        """ directly called by top-level boot command. """
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.boot_simulation_passes(use_mock_instances_for_testing)

    def kill(self) -> None:
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.kill_simulation_passes(use_mock_instances_for_testing)

    def run_workload(self) -> None:
        use_mock_instances_for_testing = False
        self.firesim_topology_with_passes.run_workload_passes(use_mock_instances_for_testing)



