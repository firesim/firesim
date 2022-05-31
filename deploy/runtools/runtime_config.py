""" This file manages the overall configuration of the system for running
simulation tasks. """

from __future__ import annotations

from datetime import timedelta
from time import strftime, gmtime
import pprint
import logging
import yaml
import os
import sys
from fabric.api import prefix, settings, local # type: ignore
from copy import deepcopy

from awstools.awstools import aws_resource_names
from awstools.afitools import get_firesim_tagval_for_agfi
from runtools.firesim_topology_with_passes import FireSimTopologyWithPasses
from runtools.workload import WorkloadConfig
from runtools.run_farm import RunFarm
from runtools.simulation_data_classes import TracerVConfig, AutoCounterConfig, HostDebugConfig, SynthPrintConfig
from util.streamlogger import StreamLogger
from util.inheritors import inheritors

from typing import Optional, Dict, Any, List, Sequence, TYPE_CHECKING
import argparse # this is not within a if TYPE_CHECKING: scope so the `register_task` in FireSim can evaluate it's annotation
if TYPE_CHECKING:
    from runtools.utils import MacAddress

LOCAL_DRIVERS_BASE = "../sim/output/"
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

        # TODO: this will change based on the "what-to-build" PR
        #self.agfi = None
        #self.xclbin = None
        #self.platform = hwconfig_dict['platform']
        #if self.platform == 'vitis':
        #    self.xclbin = hwconfig_dict['xclbin']
        #elif self.platform == 'f1':
        #    self.agfi = hwconfig_dict['agfi']
        self.platform = "f1"
        self.agfi = hwconfig_dict['agfi']

        self.deploytriplet = hwconfig_dict['deploy_triplet_override']
        if self.deploytriplet is not None:
            rootLogger.warning("{} is overriding a deploy triplet in your config_hwdb.yaml file. Make sure you understand why!".format(name))
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
        return self.get_design_name() + "-" + self.platform

    def get_local_driver_path(self) -> str:
        """ return relative local path of the driver used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + self.platform + "/" + my_deploytriplet + "/"
        fpga_driver_local = drivers_software_base + self.get_local_driver_binaryname()
        return fpga_driver_local

    def get_local_runtimeconf_binaryname(self) -> str:
        """ Get the name of the runtimeconf file. """
        return "runtime.conf" if self.customruntimeconfig is None else os.path.basename(self.customruntimeconfig)

    def get_local_runtime_conf_path(self) -> str:
        """ return relative local path of the runtime conf used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_BASE + "/" + self.platform + "/" + my_deploytriplet + "/"
        my_runtimeconfig = self.customruntimeconfig
        if my_runtimeconfig is None:
            runtime_conf_local = drivers_software_base + "runtime.conf"
        else:
            runtime_conf_local = CUSTOM_RUNTIMECONFS_BASE + my_runtimeconfig
        return runtime_conf_local

    def get_boot_simulation_command(self,
            slotid: int,
            all_macs: Sequence[MacAddress],
            all_rootfses: Sequence[Optional[str]],
            all_linklatencies: Sequence[int],
            all_netbws: Sequence[int],
            profile_interval: int,
            all_bootbinaries: List[str],
            all_shmemportnames: List[str],
            tracerv_config: TracerVConfig,
            autocounter_config: AutoCounterConfig,
            hostdebug_config: HostDebugConfig,
            synthprint_config: SynthPrintConfig) -> str:
        """ return the command used to boot the simulation. this has to have
        some external params passed to it, because not everything is contained
        in a runtimehwconfig. TODO: maybe runtimehwconfig should be renamed to
        pre-built runtime config? It kinda contains a mix of pre-built and
        runtime parameters currently. """

        # TODO: supernode support
        tracefile = "+tracefile=TRACEFILE" if tracerv_config.enable else ""
        autocounterfile = "+autocounter-filename-base=AUTOCOUNTERFILE"

        # this monstrosity boots the simulator, inside screen, inside script
        # the sed is in there to get rid of newlines in runtime confs
        driver = self.get_local_driver_binaryname()
        runtimeconf = self.get_local_runtimeconf_binaryname()

        def array_to_plusargs(valuesarr: Sequence[Optional[Any]], plusarg: str) -> List[str]:
            args = []
            for index, arg in enumerate(valuesarr):
                if arg is not None:
                    args.append("""{}{}={}""".format(plusarg, index, arg))
            return args

        def array_to_lognames(values: Sequence[Optional[Any]], prefix: str) -> List[str]:
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
        zero_out_dram = "+zero-out-dram" if (hostdebug_config.zero_out_dram) else ""
        disable_asserts = "+disable-asserts" if (hostdebug_config.disable_synth_asserts) else ""
        print_cycle_prefix = "+print-no-cycle-prefix" if not synthprint_config.cycle_prefix else ""

        # TODO supernode support
        dwarf_file_name = "+dwarf-file-name=" + all_bootbinaries[0] + "-dwarf"

        screen_name = "fsim{}".format(slotid)
        run_device_placement = "+slotid={}".format(slotid) if self.platform == "f1" else "+device_index={}".format(slotid)
        # TODO: re-enable for vitis
        #other = "+binary_file={}".format(self.xclbin) if self.platform == "vitis" else ""

        # TODO: supernode support (tracefile, trace-select.. etc)
        permissive_driver_args = []
        permissive_driver_args += [f"$(sed \':a;N;$!ba;s/\\n/ /g\' {runtimeconf})"]
        permissive_driver_args += [run_device_placement]
        permissive_driver_args += [f"+profile-interval={profile_interval}"]
        permissive_driver_args += [zero_out_dram]
        permissive_driver_args += [disable_asserts]
        permissive_driver_args += command_macs
        permissive_driver_args += command_rootfses
        permissive_driver_args += command_niclogs
        permissive_driver_args += command_blkdev_logs
        permissive_driver_args += [f"{tracefile}", f"+trace-select={tracerv_config.select}", f"+trace-start={tracerv_config.start}", f"+trace-end={tracerv_config.end}", f"+trace-output-format={tracerv_config.output_format}", dwarf_file_name]
        permissive_driver_args += [f"+autocounter-readrate={autocounter_config.readrate}", autocounterfile]
        permissive_driver_args += [command_dromajo]
        permissive_driver_args += [print_cycle_prefix, f"+print-start={synthprint_config.start}", f"+print-end={synthprint_config.end}"]
        permissive_driver_args += command_linklatencies
        permissive_driver_args += command_netbws
        permissive_driver_args += command_shmemportnames
        driver_call = f"""sudo ./{driver} +permissive {" ".join(permissive_driver_args)} +permissive-off {" ".join(command_bootbinaries)}"""
        base_command = f"""script -f -c 'stty intr ^] && {driver_call} && stty intr ^c' uartlog"""
        screen_wrapped = f"""screen -S {screen_name} -d -m bash -c "{base_command}"; sleep 1"""

        return screen_wrapped

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
                driverbuildcommand = """make DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} PLATFORM={} driver""".format(design, target_config, platform_config, self.platform)
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
    run_farm_requested_name: str
    run_farm_dispatcher: RunFarm
    topology: str
    no_net_num_nodes: int
    linklatency: int
    switchinglatency: int
    netbandwidth: int
    profileinterval: int
    launch_timeout: timedelta
    always_expand: bool
    tracerv_config: TracerVConfig
    autocounter_config: AutoCounterConfig
    hostdebug_config: HostDebugConfig
    synthprint_config: SynthPrintConfig
    workload_name: str
    suffixtag: str
    terminateoncompletion: bool

    def __init__(self, runtimeconfigfile: str, configoverridedata: str) -> None:

        runtime_configfile = None
        with open(runtimeconfigfile, "r") as yaml_file:
            runtime_configfile = yaml.safe_load(yaml_file)

        runtime_dict = runtime_configfile

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

        # Setup the run farm
        defaults_file = runtime_dict['run_farm_config']['defaults']
        with open(defaults_file, "r") as yaml_file:
            run_farm_configfile = yaml.safe_load(yaml_file)
        run_farm_type = run_farm_configfile["run_farm_type"]
        run_farm_args = run_farm_configfile["args"]

        # add the overrides if it exists

        # taken from https://gist.github.com/angstwad/bf22d1822c38a92ec0a9
        def deep_merge(a: dict, b: dict) -> dict:
            result = deepcopy(a)
            for bk, bv in b.items():
                av = result.get(bk)
                if isinstance(av, dict) and isinstance(bv, dict):
                    result[bk] = deep_merge(av, bv)
                else:
                    result[bk] = deepcopy(bv)
            return result

        override_args = runtime_dict['run_farm_config'].get('override_args')
        if override_args:
            run_farm_args = deep_merge(run_farm_args, override_args)

        run_farm_dispatch_dict = dict([(x.__name__, x) for x in inheritors(RunFarm)])

        # create dispatcher object using class given and pass args to it
        self.run_farm_dispatcher = run_farm_dispatch_dict[run_farm_type](run_farm_args)

        self.topology = runtime_dict['target_config']['topology']
        self.no_net_num_nodes = int(runtime_dict['target_config']['no_net_num_nodes'])
        self.linklatency = int(runtime_dict['target_config']['link_latency'])
        self.switchinglatency = int(runtime_dict['target_config']['switching_latency'])
        self.netbandwidth = int(runtime_dict['target_config']['net_bandwidth'])
        self.profileinterval = int(runtime_dict['target_config']['profile_interval'])

        self.tracerv_config = TracerVConfig()
        if 'tracing' in runtime_dict:
            self.tracerv_config.enable = runtime_dict['tracing'].get('enable') == "yes"
            self.tracerv_config.select = runtime_dict['tracing'].get('selector', "0")
            self.tracerv_config.start = runtime_dict['tracing'].get('start', "0")
            self.tracerv_config.end = runtime_dict['tracing'].get('end', "-1")
            self.tracerv_config.output_format = runtime_dict['tracing'].get('output_format', "0")
        self.autocounter_config = AutoCounterConfig()
        if 'autocounter' in runtime_dict:
            self.autocounter_config.readrate = int(runtime_dict['autocounter'].get('read_rate', "0"))
        self.defaulthwconfig = runtime_dict['target_config']['default_hw_config']
        self.hostdebug_config = HostDebugConfig()
        if 'host_debug' in runtime_dict:
            self.hostdebug_config.zero_out_dram = runtime_dict['host_debug'].get('zero_out_dram') == "yes"
            self.hostdebug_config.disable_synth_asserts = runtime_dict['host_debug'].get('disable_synth_asserts') == "yes"
        self.synthprint_config = SynthPrintConfig()
        if 'synth_print' in runtime_dict:
            self.synthprint_config.start = runtime_dict['synth_print'].get("start", "0")
            self.synthprint_config.end = runtime_dict['synth_print'].get("end", "-1")
            self.synthprint_config.cycle_prefix = runtime_dict['synth_print'].get("cycle_prefix", "yes") == "yes"

        self.workload_name = runtime_dict['workload']['workload_name']
        # an extra tag to differentiate workloads with the same name in results names
        self.suffixtag = runtime_dict['workload']['suffix_tag'] if 'suffix_tag' in runtime_dict['workload'] else None
        self.terminateoncompletion = runtime_dict['workload']['terminate_on_completion'] == "yes"

    def __str__(self) -> str:
        return pprint.pformat(vars(self))

class RuntimeConfig:
    """ This class manages the overall configuration of the manager for running
    simulation tasks. """
    launch_time: str
    args: argparse.Namespace
    runtimehwdb: RuntimeHWDB
    innerconf: InnerRuntimeConfiguration
    run_farm: RunFarm
    workload: WorkloadConfig
    firesim_topology_with_passes: FireSimTopologyWithPasses

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

        self.run_farm = self.innerconf.run_farm_dispatcher

        # construct a privateip -> instance obj mapping for later use
        #self.instancelookuptable = instance_privateip_lookup_table(
        #    f1_16_instances + f1_2_instances + m4_16_instances)

        # setup workload config obj, aka a list of workloads that can be assigned
        # to a server
        self.workload = WorkloadConfig(self.innerconf.workload_name, self.launch_time,
                                       self.innerconf.suffixtag)

        # start constructing the target configuration tree
        self.firesim_topology_with_passes = FireSimTopologyWithPasses(
            self.innerconf.topology, self.innerconf.no_net_num_nodes,
            self.run_farm, self.runtimehwdb, self.innerconf.defaulthwconfig,
            self.workload, self.innerconf.linklatency,
            self.innerconf.switchinglatency, self.innerconf.netbandwidth,
            self.innerconf.profileinterval,
            self.innerconf.tracerv_config,
            self.innerconf.autocounter_config,
            self.innerconf.hostdebug_config,
            self.innerconf.synthprint_config,
            self.innerconf.terminateoncompletion)

    def launch_run_farm(self) -> None:
        """ directly called by top-level launchrunfarm command. """
        self.run_farm.launch_run_farm()

    def terminate_run_farm(self) -> None:
        """ directly called by top-level terminaterunfarm command. """
        terminate_some_dict = {}
        if self.args.terminatesome is not None:
            for pair in self.args.terminatesome:
                terminate_some_dict[pair[0]] = pair[1]

        def old_style_terminate_args(instance_type, arg_val, arg_flag_str):
            if arg_val != -1:
                rootLogger.critical("WARNING: You are using the old-style " + arg_flag_str + " flag. See the new --terminatesome flag in help. The old-style flag will be removed in the next major FireSim release (1.15.X).")
                terminate_some_dict[instance_type] = arg_val

        old_style_terminate_args('f1.16xlarge', self.args.terminatesomef116, '--terminatesomef116')
        old_style_terminate_args('f1.4xlarge', self.args.terminatesomef14, '--terminatesomef14')
        old_style_terminate_args('f1.2xlarge', self.args.terminatesomef12, '--terminatesomef12')
        old_style_terminate_args('m4.16xlarge', self.args.terminatesomem416, '--terminatesomem416')

        self.run_farm.terminate_run_farm(terminate_some_dict, self.args.forceterminate)

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
