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

from awstools.awstools import aws_resource_names
from awstools.afitools import get_firesim_tagval_for_agfi
from runtools.firesim_topology_with_passes import FireSimTopologyWithPasses
from runtools.workload import WorkloadConfig
from runtools.run_farm import RunFarm
from runtools.simulation_data_classes import TracerVConfig, AutoCounterConfig, HostDebugConfig, SynthPrintConfig
from util.streamlogger import StreamLogger
from util.inheritors import inheritors
from util.deepmerge import deep_merge

from typing import Optional, Dict, Any, List, Sequence, Tuple, TYPE_CHECKING
import argparse # this is not within a if TYPE_CHECKING: scope so the `register_task` in FireSim can evaluate it's annotation
if TYPE_CHECKING:
    from runtools.utils import MacAddress

LOCAL_DRIVERS_BASE = "../sim/output/"
LOCAL_DRIVERS_GENERATED_SRC = "../sim/generated-src/"
CUSTOM_RUNTIMECONFS_BASE = "../sim/custom-runtime-configs/"

rootLogger = logging.getLogger()

class RuntimeHWConfig:
    """ A pythonic version of the entires in config_hwdb.yaml """
    name: str
    platform: str

    # TODO: should be abstracted out between platforms with a URI
    agfi: Optional[str]
    """User-specified, possibly-URI, path to xclbin"""
    xclbin: Optional[str]
    """RunFarmHost-local path to xclbin"""
    local_xclbin: Optional[str]

    deploytriplet: Optional[str]
    customruntimeconfig: str
    driver_built: bool
    additional_required_files: List[Tuple[str, str]]
    driver_name_prefix: str
    driver_name_suffix: str
    local_driver_base_dir: str
    driver_build_target: str
    driver_type_message: str

    def __init__(self, name: str, hwconfig_dict: Dict[str, Any]) -> None:
        self.name = name

        if 'agfi' in hwconfig_dict and 'xclbin' in hwconfig_dict:
            raise Exception(f"Unable to have agfi and xclbin in HWDB entry {name}.")

        self.agfi = hwconfig_dict.get('agfi')
        self.xclbin = hwconfig_dict.get('xclbin')
        self.local_xclbin = None

        if self.agfi is not None:
            self.platform = "f1"
        else:
            self.platform = "vitis"

        self.driver_name_prefix = ""
        self.driver_name_suffix = "-" + self.platform

        self.local_driver_base_dir = LOCAL_DRIVERS_BASE
        self.driver_type_message = "FPGA software"
        self.driver_build_target = self.platform

        self.deploytriplet = hwconfig_dict['deploy_triplet_override']
        if self.deploytriplet is not None:
            rootLogger.warning("{} is overriding a deploy triplet in your config_hwdb.yaml file. Make sure you understand why!".format(name))

        # TODO: obtain deploy_triplet from tag in xclbin
        if self.deploytriplet is None and self.platform == "vitis":
            raise Exception(f"Must set the deploy_triplet_override for Vitis bitstreams")

        self.customruntimeconfig = hwconfig_dict['custom_runtime_config']
        # note whether we've built a copy of the simulation driver for this hwconf
        self.driver_built = False

        self.additional_required_files = []

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
        return self.driver_name_prefix + self.get_design_name() + self.driver_name_suffix

    def get_local_driver_dir(self) -> str:
        """ Get the relative local directory that contains the driver used to
        run this sim. """
        return self.local_driver_base_dir + "/" + self.platform + "/" + self.get_deploytriplet_for_config() + "/"

    def get_local_driver_path(self) -> str:
        """ return relative local path of the driver used to run this sim. """
        return self.get_local_driver_dir() + self.get_local_driver_binaryname()

    def get_local_runtimeconf_binaryname(self) -> str:
        """ Get the name of the runtimeconf file. """
        return "FireSim-generated.runtime.conf" if self.customruntimeconfig is None else os.path.basename(self.customruntimeconfig)

    def get_local_runtime_conf_path(self) -> str:
        """ return relative local path of the runtime conf used to run this sim. """
        my_deploytriplet = self.get_deploytriplet_for_config()
        drivers_software_base = LOCAL_DRIVERS_GENERATED_SRC + "/" + self.platform + "/" + my_deploytriplet + "/"
        my_runtimeconfig = self.customruntimeconfig
        if my_runtimeconfig is None:
            runtime_conf_local = drivers_software_base + self.get_local_runtimeconf_binaryname()
        else:
            runtime_conf_local = CUSTOM_RUNTIMECONFS_BASE + my_runtimeconfig
        return runtime_conf_local

    def get_additional_required_sim_files(self) -> List[Tuple[str, str]]:
        """ return list of any additional files required to run a simulation.
        """
        return self.additional_required_files

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
            synthprint_config: SynthPrintConfig,
            sudo: bool,
            extra_plusargs: str = "",
            extra_args: str = "") -> str:
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
        run_device_placement = "+slotid={}".format(slotid)

        if self.platform == "vitis":
            assert self.local_xclbin is not None
            vitis_bit = "+binary_file={}".format(self.local_xclbin)
        else:
            vitis_bit = ""

        # TODO: supernode support (tracefile, trace-select.. etc)
        permissive_driver_args = []
        permissive_driver_args += [f"$(sed \':a;N;$!ba;s/\\n/ /g\' {runtimeconf})"]
        permissive_driver_args += [run_device_placement]
        permissive_driver_args += [vitis_bit]
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
        driver_call = f"""{"sudo" if sudo else ""} ./{driver} +permissive {" ".join(permissive_driver_args)} {extra_plusargs} +permissive-off {" ".join(command_bootbinaries)} {extra_args} """
        base_command = f"""script -f -c 'stty intr ^] && {driver_call} && stty intr ^c' uartlog"""
        screen_wrapped = f"""screen -S {screen_name} -d -m bash -c "{base_command}"; sleep 1"""

        return screen_wrapped

    def get_kill_simulation_command(self) -> str:
        driver = self.get_local_driver_binaryname()
        # Note that pkill only works for names <=15 characters
        return """pkill -SIGKILL {driver}""".format(driver=driver[:15])

    def build_sim_driver(self) -> None:
        """ Build driver for running simulation """
        if self.driver_built:
            # we already built the driver at some point
            return
        # TODO there is a duplicate of this in runtools
        triplet_pieces = self.get_deploytriplet_for_config().split("-")
        design = triplet_pieces[0]
        target_config = triplet_pieces[1]
        platform_config = triplet_pieces[2]
        rootLogger.info(f"Building {self.driver_type_message} driver for {str(self.get_deploytriplet_for_config())}")
        with prefix('cd ../'), \
             prefix('export RISCV={}'.format(os.getenv('RISCV', ""))), \
             prefix('export PATH={}'.format(os.getenv('PATH', ""))), \
             prefix('export LD_LIBRARY_PATH={}'.format(os.getenv('LD_LIBRARY_PATH', ""))), \
             prefix('source ./sourceme-f1-manager.sh'), \
             prefix('cd sim/'), \
             StreamLogger('stdout'), \
             StreamLogger('stderr'), \
             prefix('set -o pipefail'):
            localcap = None
            with settings(warn_only=True):
                # the local driver dir must already exist for the tee to always
                # work
                local("""mkdir -p {}""".format(self.get_local_driver_dir()))
                buildlogfile = """{}firesim-manager-make-{}-temp-output-log""".format(self.get_local_driver_dir(), self.driver_build_target)
                driverbuildcommand = """make DESIGN={} TARGET_CONFIG={} PLATFORM_CONFIG={} PLATFORM={} {}""" .format(design, target_config, platform_config, self.platform, self.driver_build_target)
                driverbuildcommand_full = driverbuildcommand + """ 2>&1 | tee {}""".format(buildlogfile)
                localcap = local(driverbuildcommand_full)
                logcapture = local("""cat {}""".format(buildlogfile), capture=True)
            rootLogger.debug("[localhost] " + str(logcapture))
            if localcap.failed:
                rootLogger.info(f"{self.driver_type_message} driver build failed. Exiting. See log for details.")
                rootLogger.info("""You can also re-run '{}' in the 'firesim/sim' directory to debug this error.""".format(driverbuildcommand))
                sys.exit(1)

        self.driver_built = True


    def __str__(self) -> str:
        return """RuntimeHWConfig: {}\nDeployTriplet: {}\nAGFI: {}\nXCLBIN: {}\nCustomRuntimeConf: {}""".format(self.name, self.deploytriplet, self.agfi, self.xclbin, str(self.customruntimeconfig))




class RuntimeBuildRecipeConfig(RuntimeHWConfig):
    """ A pythonic version of the entires in config_build_recipes.yaml """

    def __init__(self, name: str, build_recipe_dict: Dict[str, Any],
                 default_metasim_host_sim: str,
                 metasimulation_only_plusargs: str,
                 metasimulation_only_vcs_plusargs: str) -> None:
        self.name = name
        self.agfi = None
        self.xclbin = None
        self.deploytriplet = build_recipe_dict['DESIGN'] + "-" + build_recipe_dict['TARGET_CONFIG'] + "-" + build_recipe_dict['PLATFORM_CONFIG']

        self.customruntimeconfig = build_recipe_dict['metasim_customruntimeconfig']
        # note whether we've built a copy of the simulation driver for this hwconf
        self.driver_built = False
        self.metasim_host_simulator = default_metasim_host_sim

        self.platform = "f1"
        self.driver_name_prefix = ""
        self.driver_name_suffix = ""
        if self.metasim_host_simulator in ["verilator", "verilator-debug"]:
            self.driver_name_prefix = "V"
        if self.metasim_host_simulator in ['verilator-debug', 'vcs-debug']:
            self.driver_name_suffix = "-debug"

        self.local_driver_base_dir = LOCAL_DRIVERS_GENERATED_SRC

        dramsim_pair = (self.get_local_driver_dir() + "dramsim2_ini", "")
        self.additional_required_files = [dramsim_pair]

        self.driver_build_target = self.metasim_host_simulator
        self.driver_type_message = "Metasim"

        self.metasimulation_only_plusargs = metasimulation_only_plusargs
        self.metasimulation_only_vcs_plusargs = metasimulation_only_vcs_plusargs

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
            synthprint_config: SynthPrintConfig,
            sudo: bool,
            extra_plusargs: str = "",
            extra_args: str = "") -> str:
        """ return the command used to boot the meta simulation. """
        full_extra_plusargs = " " + self.metasimulation_only_plusargs + " " + extra_plusargs
        if self.metasim_host_simulator in ['vcs', 'vcs-debug']:
            full_extra_plusargs = " " + self.metasimulation_only_vcs_plusargs + " " +  full_extra_plusargs
        if self.metasim_host_simulator in ['verilator-debug', 'vcs-debug']:
            full_extra_plusargs += " +waveform=metasim_waveform.vpd "
        # TODO: spike-dasm support
        full_extra_args = " 2> metasim_stderr.out " + extra_args
        return super(RuntimeBuildRecipeConfig, self).get_boot_simulation_command(
            slotid,
            all_macs,
            all_rootfses,
            all_linklatencies,
            all_netbws,
            profile_interval,
            all_bootbinaries,
            all_shmemportnames,
            tracerv_config,
            autocounter_config,
            hostdebug_config,
            synthprint_config,
            sudo,
            full_extra_plusargs,
            full_extra_args)

class RuntimeHWDB:
    """ This class manages the hardware configurations that are available
    as endpoints on the simulation. """
    hwconf_dict: Dict[str, RuntimeHWConfig]
    config_file_name: str
    simulation_mode_string: str

    def __init__(self, hardwaredbconfigfile: str) -> None:
        self.config_file_name = hardwaredbconfigfile
        self.simulation_mode_string = "FPGA simulation"

        agfidb_configfile = None
        with open(hardwaredbconfigfile, "r") as yaml_file:
            agfidb_configfile = yaml.safe_load(yaml_file)

        agfidb_dict = agfidb_configfile

        self.hwconf_dict = {s: RuntimeHWConfig(s, v) for s, v in agfidb_dict.items()}

    def keyerror_message(self, name: str) -> str:
        """ Return the error message for lookup errors."""
        return f"'{name}' not found in '{self.config_file_name}', which is used to specify target design descriptions for {self.simulation_mode_string}s."

    def get_runtimehwconfig_from_name(self, name: str) -> RuntimeHWConfig:
        if name not in self.hwconf_dict:
            raise KeyError(self.keyerror_message(name))
        return self.hwconf_dict[name]

    def __str__(self) -> str:
        return pprint.pformat(vars(self))

class RuntimeBuildRecipes(RuntimeHWDB):
    """ Same as RuntimeHWDB, but use information from build recipes entries
    instead of hwdb for metasimulation."""

    def __init__(self, build_recipes_config_file: str,
                 metasim_host_simulator: str,
                 metasimulation_only_plusargs: str,
                 metasimulation_only_vcs_plusargs: str) -> None:
        self.config_file_name = build_recipes_config_file
        self.simulation_mode_string = "Metasimulation"

        recipes_configfile = None
        with open(build_recipes_config_file, "r") as yaml_file:
            recipes_configfile = yaml.safe_load(yaml_file)

        recipes_dict = recipes_configfile

        self.hwconf_dict = {s: RuntimeBuildRecipeConfig(s, v, metasim_host_simulator, metasimulation_only_plusargs, metasimulation_only_vcs_plusargs) for s, v in recipes_dict.items()}


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
    metasimulation_enabled: bool
    metasimulation_host_simulator: str
    metasimulation_only_plusargs: str
    metasimulation_only_vcs_plusargs: str
    default_plusarg_passthrough: str

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

        def dict_assert(key_check, dict_name):
            assert key_check in dict_name, f"FAIL: missing {key_check} in runtime config."

        dict_assert('metasimulation', runtime_dict)
        metasim_dict = runtime_dict['metasimulation']
        dict_assert('metasimulation_enabled', metasim_dict)
        self.metasimulation_enabled = metasim_dict['metasimulation_enabled']
        dict_assert('metasimulation_host_simulator', metasim_dict)
        self.metasimulation_host_simulator = metasim_dict['metasimulation_host_simulator']
        dict_assert('metasimulation_only_plusargs', metasim_dict)
        self.metasimulation_only_plusargs = metasim_dict['metasimulation_only_plusargs']
        dict_assert('metasimulation_only_vcs_plusargs', metasim_dict)
        self.metasimulation_only_vcs_plusargs = metasim_dict['metasimulation_only_vcs_plusargs']

        # Setup the run farm
        defaults_file = runtime_dict['run_farm']['base_recipe']
        with open(defaults_file, "r") as yaml_file:
            run_farm_configfile = yaml.safe_load(yaml_file)
        run_farm_type = run_farm_configfile["run_farm_type"]
        run_farm_args = run_farm_configfile["args"]

        # add the overrides if it exists

        override_args = runtime_dict['run_farm'].get('recipe_arg_overrides')
        if override_args:
            run_farm_args = deep_merge(run_farm_args, override_args)

        run_farm_dispatch_dict = dict([(x.__name__, x) for x in inheritors(RunFarm)])

        if not run_farm_type in run_farm_dispatch_dict:
            raise Exception(f"Unable to find {run_farm_type} in available run farm classes: {run_farm_dispatch_dict.keys()}")

        # create dispatcher object using class given and pass args to it
        self.run_farm_dispatcher = run_farm_dispatch_dict[run_farm_type](run_farm_args, self.metasimulation_enabled)

        self.topology = runtime_dict['target_config']['topology']
        self.no_net_num_nodes = int(runtime_dict['target_config']['no_net_num_nodes'])
        self.linklatency = int(runtime_dict['target_config']['link_latency'])
        self.switchinglatency = int(runtime_dict['target_config']['switching_latency'])
        self.netbandwidth = int(runtime_dict['target_config']['net_bandwidth'])
        self.profileinterval = int(runtime_dict['target_config']['profile_interval'])
        self.defaulthwconfig = runtime_dict['target_config']['default_hw_config']

        self.tracerv_config = TracerVConfig(runtime_dict.get('tracing', {}))
        self.autocounter_config = AutoCounterConfig(runtime_dict.get('autocounter', {}))
        self.hostdebug_config = HostDebugConfig(runtime_dict.get('host_debug', {}))
        self.synthprint_config = SynthPrintConfig(runtime_dict.get('synth_print', {}))

        dict_assert('plusarg_passthrough', runtime_dict['target_config'])
        self.default_plusarg_passthrough = runtime_dict['target_config']['plusarg_passthrough']

        self.workload_name = runtime_dict['workload']['workload_name']
        # an extra tag to differentiate workloads with the same name in results names
        self.suffixtag = runtime_dict['workload']['suffix_tag'] if 'suffix_tag' in runtime_dict['workload'] else None
        self.terminateoncompletion = runtime_dict['workload']['terminate_on_completion'] == True

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
    runtime_build_recipes: RuntimeBuildRecipes

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

        self.runtime_build_recipes = RuntimeBuildRecipes(args.buildrecipesconfigfile, self.innerconf.metasimulation_host_simulator, self.innerconf.metasimulation_only_plusargs, self.innerconf.metasimulation_only_vcs_plusargs)
        rootLogger.debug(self.runtime_build_recipes)

        self.run_farm = self.innerconf.run_farm_dispatcher

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
            self.innerconf.terminateoncompletion,
            self.runtime_build_recipes,
            self.innerconf.metasimulation_enabled,
            self.innerconf.default_plusarg_passthrough)

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

    def build_driver(self) -> None:
        """ directly called by top-level builddriver command. """
        self.firesim_topology_with_passes.build_driver_passes()

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
