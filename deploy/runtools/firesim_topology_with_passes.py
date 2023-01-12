""" This constructs a topology and performs a series of passes on it. """

from __future__ import annotations

import time
import os
import pprint
import logging
import datetime
import sys
from fabric.api import env, parallel, execute, run, local, warn_only # type: ignore
from colorama import Fore, Style # type: ignore
from functools import reduce

from runtools.firesim_topology_elements import FireSimServerNode, FireSimDummyServerNode, FireSimSwitchNode
from runtools.firesim_topology_core import FireSimTopology
from runtools.utils import MacAddress
from runtools.simulation_data_classes import TracerVConfig, AutoCounterConfig, HostDebugConfig, SynthPrintConfig

from typing import Dict, Any, cast, List, TYPE_CHECKING, Callable
if TYPE_CHECKING:
    from runtools.run_farm import RunFarm
    from runtools.runtime_config import RuntimeHWDB, RuntimeBuildRecipes
    from runtools.workload import WorkloadConfig

rootLogger = logging.getLogger()

@parallel
def instance_liveness() -> None:
    """ Confirm that all instances are accessible (are running and can be
    ssh'ed into) first so that we don't run any actual firesim-related commands
    on only some of the run farm machines.

    Also confirm that the default shell in use is one that is known to handle
    commands we pass to run() in the manager. The default shell must be able to
    handle our command strings because it is always the first to interpret the
    command string, even if the command string starts with /bin/bash.

    To my knowledge, it is not possible to specify a different shell for
    a specific instance of ssh-ing into a machine. The only way to control what
    shell the command is handed to is to set the default shell. As reported in:
    https://serverfault.com/questions/162018/force-ssh-to-use-a-specific-shell

    For shell handling, this function will do the following:
    a) For known good shells (specified in "allowed_shells"), continue normally.
    b) For known bad shells (specified in "disallowed_shells"), report error and
        exit immediately.
    c) For unknown shells, print a warning and continue normally.
    """
    rootLogger.info("""[{}] Checking if host instance is up...""".format(env.host_string))
    run("uname -a")
    collect = run("echo $SHELL")

    allowed_shells = ["bash"]
    disallowed_shells = ["csh"]

    shell_info = collect.stdout.split("/")[-1]
    if shell_info in allowed_shells:
        return
    if shell_info in disallowed_shells:
        rootLogger.error(f"::ERROR:: Invalid default shell in use: {shell_info}. Allowed shells: {allowed_shells}.")
        sys.exit(1)
    rootLogger.warning(f"::WARNING:: Unknown default shell in use: {shell_info}. Allowed shells: {allowed_shells}. You are using a default shell that has not yet been tested to correctly interpret the commands run by the FireSim manager. Proceed at your own risk. If you find that your shell works correctly, please file an issue on the FireSim repo (https://github.com/firesim/firesim/issues) so that we can add your shell to the list of known good shells.")

class FireSimTopologyWithPasses:
    """ This class constructs a FireSimTopology, then performs a series of passes
    on the topology to map it all the way to something usable to deploy a simulation.
    """
    passes_used: List[str]
    user_topology_name: str
    no_net_num_nodes: int
    run_farm: RunFarm
    hwdb: RuntimeHWDB
    build_recipes: RuntimeBuildRecipes
    workload: WorkloadConfig
    firesimtopol: FireSimTopology
    defaulthwconfig: str
    defaultlinklatency: int
    defaultswitchinglatency: int
    defaultnetbandwidth: int
    defaultprofileinterval: int
    defaulttracervconfig: TracerVConfig
    defaultautocounterconfig: AutoCounterConfig
    defaulthostdebugconfig: HostDebugConfig
    defaultsynthprintconfig: SynthPrintConfig
    terminateoncompletion: bool

    def __init__(self,
            user_topology_name: str,
            no_net_num_nodes: int,
            run_farm: RunFarm,
            hwdb: RuntimeHWDB,
            defaulthwconfig: str,
            workload: WorkloadConfig,
            defaultlinklatency: int,
            defaultswitchinglatency: int,
            defaultnetbandwidth: int,
            defaultprofileinterval: int,
            defaulttracervconfig: TracerVConfig,
            defaultautocounterconfig: AutoCounterConfig,
            defaulthostdebugconfig: HostDebugConfig,
            defaultsynthprintconfig: SynthPrintConfig,
            terminateoncompletion: bool,
            build_recipes: RuntimeBuildRecipes,
            default_metasim_mode: bool,
            default_plusarg_passthrough: str) -> None:
        self.passes_used = []
        self.user_topology_name = user_topology_name
        self.no_net_num_nodes = no_net_num_nodes
        self.run_farm = run_farm
        self.hwdb = hwdb
        self.build_recipes = build_recipes
        self.workload = workload
        self.firesimtopol = FireSimTopology(user_topology_name, no_net_num_nodes)
        self.defaulthwconfig = defaulthwconfig
        self.defaultlinklatency = defaultlinklatency
        self.defaultswitchinglatency = defaultswitchinglatency
        self.defaultnetbandwidth = defaultnetbandwidth
        self.defaultprofileinterval = defaultprofileinterval
        self.terminateoncompletion = terminateoncompletion
        self.defaulttracervconfig = defaulttracervconfig
        self.defaultautocounterconfig = defaultautocounterconfig
        self.defaulthostdebugconfig = defaulthostdebugconfig
        self.defaultsynthprintconfig = defaultsynthprintconfig
        self.default_metasim_mode = default_metasim_mode
        self.default_plusarg_passthrough = default_plusarg_passthrough

        self.phase_one_passes()

    def pass_assign_mac_addresses(self) -> None:
        """ DFS through the topology to assign mac addresses """
        self.passes_used.append("pass_assign_mac_addresses")

        nodes_dfs_order = self.firesimtopol.get_dfs_order()
        MacAddress.reset_allocator()
        for node in nodes_dfs_order:
            if isinstance(node, FireSimServerNode):
                node.assign_mac_address(MacAddress())

    def pass_compute_switching_tables(self) -> None:
        """ This creates the MAC addr -> port lists for switch nodes.

        a) First, a pass that computes "downlinkmacs" for each node, which
        represents all of the MAC addresses that are reachable on the downlinks
        of this switch, to advertise to uplinks.

        b) Next, a pass that actually constructs the MAC addr -> port lists
        for switch nodes.

        It is assumed that downlinks take ports [0, num downlinks) and
        uplinks take ports [num downlinks, num downlinks + num uplinks)

        This will currently just assume that there is one uplink, since the
        switch models do not handle load balancing across multiple paths.
        """

        # this pass requires mac addresses to already be assigned
        assert "pass_assign_mac_addresses" in self.passes_used
        self.passes_used.append("pass_compute_switching_tables")

        nodes_dfs_order = self.firesimtopol.get_dfs_order()
        for node in nodes_dfs_order:
            if isinstance(node, FireSimServerNode):
                node.downlinkmacs = [node.get_mac_address()]
            else:
                childdownlinkmacs: List[List[MacAddress]] = []
                for x in node.downlinks:
                    childdownlinkmacs.append(x.get_downlink_side().downlinkmacs)

                # flatten
                node.downlinkmacs = reduce(lambda x, y: x + y, childdownlinkmacs)

        switches_dfs_order = self.firesimtopol.get_dfs_order_switches()

        for switch in switches_dfs_order:
            uplinkportno = len(switch.downlinks)

            # prepopulate the table with the last port, which will be
            switchtab = [uplinkportno for x in range(MacAddress.next_mac_to_allocate())]
            for port_no in range(len(switch.downlinks)):
                portmacs = switch.downlinks[port_no].get_downlink_side().downlinkmacs
                for mac in portmacs:
                    switchtab[mac.as_int_no_prefix()] = port_no

            switch.switch_table = switchtab

    def pass_create_topology_diagram(self) -> None:
        """ Produce a PDF that shows a diagram of the network.
        Useful for debugging passes to see what has been done to particular
        nodes. """
        from graphviz import Digraph # type: ignore

        gviz_graph = Digraph('gviz_graph', filename='generated-topology-diagrams/firesim_topology'
                             + self.user_topology_name + '.gv',
                             node_attr={'shape': 'record', 'height': '.1'})

        # add all nodes to the graph
        nodes_dfs_order = self.firesimtopol.get_dfs_order()
        for node in nodes_dfs_order:
            nodehost = node.get_host_instance()
            with gviz_graph.subgraph(name='cluster_' + str(nodehost), node_attr={'shape': 'box'}) as cluster:
                cluster.node(str(node), node.diagramstr())
                cluster.attr(label=str(nodehost))


        # add all edges to the graph
        switches_dfs_order = self.firesimtopol.get_dfs_order_switches()
        for node in switches_dfs_order:
            for downlink in node.downlinks:
                downlink_side = downlink.get_downlink_side()
                gviz_graph.edge(str(node), str(downlink_side))

        gviz_graph.render(view=False)

    def pass_no_net_host_mapping(self) -> None:
        # only if we have no networks - pack simulations
        # assumes the user has provided enough or more slots
        servers = self.firesimtopol.get_dfs_order_servers()
        serverind = 0

        while len(servers) > serverind:
            # this call will error if no such instances are available.
            instance_handle = self.run_farm.get_smallest_sim_host_handle(num_sims=1)
            allocd_instance = self.run_farm.allocate_sim_host(instance_handle)

            for x in range(allocd_instance.MAX_SIM_SLOTS_ALLOWED):
                allocd_instance.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return

    def pass_simple_networked_host_node_mapping(self) -> None:
        """ A very simple host mapping strategy.  """
        switches = self.firesimtopol.get_dfs_order_switches()


        for switch in switches:
            # Filter out FireSimDummyServerNodes for actually deploying.
            # Infrastructure after this point will automatically look at the
            # FireSimDummyServerNodes if a FireSimSuperNodeServerNode is used
            alldownlinknodes = list(map(lambda x: x.get_downlink_side(), [downlink for downlink in switch.downlinks if not isinstance(downlink.get_downlink_side(), FireSimDummyServerNode)]))
            if all([isinstance(x, FireSimSwitchNode) for x in alldownlinknodes]):
                # all downlinks are switches
                switch_host_inst_handle = self.run_farm.get_switch_only_host_handle()
                self.run_farm.allocate_sim_host(switch_host_inst_handle).add_switch(switch)
            elif all([isinstance(x, FireSimServerNode) for x in alldownlinknodes]):
                downlinknodes = cast(List[FireSimServerNode], alldownlinknodes)
                # all downlinks are simulations
                num_downlinks = len(downlinknodes)

                inst_handle_for_downlinks = self.run_farm.get_smallest_sim_host_handle(num_sims=num_downlinks)
                inst = self.run_farm.allocate_sim_host(inst_handle_for_downlinks)

                inst.add_switch(switch)
                for server in downlinknodes:
                    inst.add_simulation(server)
            else:
                assert False, "Mixed downlinks currently not supported."""

    def mapping_use_one_8_slot_node(self) -> None:
        """ Just put everything on one 8 slot node """
        switches = self.firesimtopol.get_dfs_order_switches()
        instance_handle = self.run_farm.get_smallest_sim_host_handle(num_sims=8)

        for switch in switches:
            inst = self.run_farm.allocate_sim_host(instance_handle)
            inst.add_switch(switch)
            alldownlinknodes = map(lambda x: x.get_downlink_side(), switch.downlinks)
            if all([isinstance(x, FireSimServerNode) for x in alldownlinknodes]):
                downlinknodes = cast(List[FireSimServerNode], alldownlinknodes)
                for server in downlinknodes:
                    inst.add_simulation(server)
            elif any([isinstance(x, FireSimServerNode) for x in downlinknodes]):
                assert False, "MIXED DOWNLINKS NOT SUPPORTED."

    def pass_perform_host_node_mapping(self) -> None:
        """ This pass assigns host nodes to nodes in the abstract FireSim
        configuration tree.


        This is currently not a smart mapping: If your
        top level elements are switches, it will assume you're simulating a
        networked config, """

        if self.firesimtopol.custom_mapper is None:
            """ Use default mapping strategy. The topol has not specified a
            special one. """
            # if your roots are servers, just pack as tightly as possible, since
            # you have no_net_config
            if all([isinstance(x, FireSimServerNode) for x in self.firesimtopol.roots]):
                # all roots are servers, so we're in no_net_config
                # if the user has specified any 16xlarges, we assign to them first
                self.pass_no_net_host_mapping()
            else:
                # now, we're handling the cycle-accurate networked simulation case
                # currently, we only handle the case where
                self.pass_simple_networked_host_node_mapping()
        elif callable(self.firesimtopol.custom_mapper):
            """ call the mapper fn defined in the topology itself. """
            self.firesimtopol.custom_mapper(self)
        elif isinstance(self.firesimtopol.custom_mapper, str):
            """ assume that the mapping strategy is a custom pre-defined strategy
            given in this class, supplied as a string in the topology """
            mapperfunc = getattr(self, self.firesimtopol.custom_mapper)
            mapperfunc()
        else:
            assert False, "IMPROPER MAPPING CONFIGURATION"

    def pass_apply_default_hwconfig(self) -> None:
        """ This is the default mapping pass for hardware configurations - it
        does 3 things:
            1) If a node has a hardware config assigned (as a string), replace
            it with the appropriate RuntimeHWConfig object. If it is already a
            RuntimeHWConfig object then keep it the same.
            2) If a node's hardware config is none, give it the default
            hardware config.
            3) In either case, call get_deploytriplet_for_config() once to
            make the API call and cache the result for the deploytriplet.
        """
        servers = self.firesimtopol.get_dfs_order_servers()

        runtimehwconfig_lookup_fn = self.hwdb.get_runtimehwconfig_from_name
        if self.default_metasim_mode:
            runtimehwconfig_lookup_fn = self.build_recipes.get_runtimehwconfig_from_name

        for server in servers:
            hw_cfg = server.get_server_hardware_config()
            if hw_cfg is None:
                hw_cfg = runtimehwconfig_lookup_fn(self.defaulthwconfig)
            elif isinstance(hw_cfg, str):
                hw_cfg = runtimehwconfig_lookup_fn(hw_cfg)
            hw_cfg.get_deploytriplet_for_config()
            server.set_server_hardware_config(hw_cfg)

    def pass_apply_default_params(self) -> None:
        """ If the user has not set per-node parameters in the topology,
        apply the defaults. """
        allnodes = self.firesimtopol.get_dfs_order()

        for node in allnodes:
            if isinstance(node, FireSimSwitchNode):
                if node.switch_link_latency is None:
                    node.switch_link_latency = self.defaultlinklatency
                if node.switch_switching_latency is None:
                    node.switch_switching_latency = self.defaultswitchinglatency
                if node.switch_bandwidth is None:
                    node.switch_bandwidth = self.defaultnetbandwidth

            if isinstance(node, FireSimServerNode):
                if node.server_link_latency is None:
                    node.server_link_latency = self.defaultlinklatency
                if node.server_bw_max is None:
                    node.server_bw_max = self.defaultnetbandwidth
                if node.server_profile_interval is None:
                    node.server_profile_interval = self.defaultprofileinterval
                if node.tracerv_config is None:
                    node.tracerv_config = self.defaulttracervconfig
                if node.autocounter_config is None:
                    node.autocounter_config = self.defaultautocounterconfig
                if node.hostdebug_config is None:
                    node.hostdebug_config = self.defaulthostdebugconfig
                if node.synthprint_config is None:
                    node.synthprint_config = self.defaultsynthprintconfig
                if node.plusarg_passthrough is None:
                    node.plusarg_passthrough = self.default_plusarg_passthrough

    def pass_allocate_nbd_devices(self) -> None:
        """ allocate NBD devices. this must be done here to preserve the
        data structure for use in runworkload teardown. """
        servers = self.firesimtopol.get_dfs_order_servers()
        for server in servers:
            server.allocate_nbds()

    def pass_assign_jobs(self) -> None:
        """ assign jobs to simulations. """
        servers = self.firesimtopol.get_dfs_order_servers()
        for i in range(len(servers)):
            servers[i].assign_job(self.workload.get_job(i))

    def phase_one_passes(self) -> None:
        """ These are passes that can run without requiring host-node binding.
        i.e. can be run before you have run launchrunfarm. They're run
        automatically when creating this object. """
        self.pass_assign_mac_addresses()
        self.pass_compute_switching_tables()
        self.pass_perform_host_node_mapping() # TODO: we can know ports here?
        self.pass_apply_default_hwconfig()
        self.pass_apply_default_params()
        self.pass_assign_jobs()
        self.pass_allocate_nbd_devices()

        self.pass_create_topology_diagram()

    def pass_build_required_drivers(self) -> None:
        """ Build all simulation drivers. The method we're calling here won't actually
        repeat the build process more than once per run of the manager. """

        def build_drivers_helper(servers: List[FireSimServerNode]) -> None:
            for server in servers:
                resolved_cfg = server.get_resolved_server_hardware_config()

                if resolved_cfg.driver_tar is not None:
                    rootLogger.debug(f"skipping driver build because we're using {resolved_cfg.driver_tar}")
                    continue # skip building or tarballing if we have a prebuilt one

                resolved_cfg.build_sim_driver()
                resolved_cfg.build_sim_tarball(server.get_tarball_files_paths(), server.get_tar_name())

        servers = self.firesimtopol.get_dfs_order_servers()
        execute(build_drivers_helper, servers, hosts=['localhost'])

    def pass_build_required_switches(self) -> None:
        """ Build all the switches required for this simulation. """
        # the way the switch models are designed, this requires hosts to be
        # bound to instances.
        switches = self.firesimtopol.get_dfs_order_switches()
        for switch in switches:
            switch.build_switch_sim_binary()

    def infrasetup_passes(self, use_mock_instances_for_testing: bool) -> None:
        """ extra passes needed to do infrasetup """
        self.run_farm.post_launch_binding(use_mock_instances_for_testing)

        self.pass_build_required_drivers()
        self.pass_build_required_switches()

        @parallel
        def infrasetup_node_wrapper(run_farm: RunFarm) -> None:
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node is not None
            assert my_node.instance_deploy_manager is not None
            my_node.instance_deploy_manager.infrasetup_instance()

        all_run_farm_ips = [x.get_host() for x in self.run_farm.get_all_bound_host_nodes()]
        execute(instance_liveness, hosts=all_run_farm_ips)
        execute(infrasetup_node_wrapper, self.run_farm, hosts=all_run_farm_ips)

    def build_driver_passes(self) -> None:
        """ Only run passes to build drivers. """
        self.pass_build_required_drivers()

    def boot_simulation_passes(self, use_mock_instances_for_testing: bool, skip_instance_binding: bool = False) -> None:
        """ Passes that setup for boot and boot the simulation.
        skip instance binding lets users not call the binding pass on the run_farm
        again, e.g. if this was called by runworkload (because runworkload calls
        boot_simulation_passes internally)
        TODO: the reason we need this is that somehow we're getting
        garbage results if the AWS EC2 API gets called twice by accident
        (e.g.  incorrect private IPs)
        """
        if not skip_instance_binding:
            self.run_farm.post_launch_binding(use_mock_instances_for_testing)

        @parallel
        def boot_switch_wrapper(run_farm: RunFarm) -> None:
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node is not None
            assert my_node.instance_deploy_manager is not None
            my_node.instance_deploy_manager.start_switches_instance()

        all_run_farm_ips = [x.get_host() for x in self.run_farm.get_all_bound_host_nodes()]
        execute(instance_liveness, hosts=all_run_farm_ips)
        execute(boot_switch_wrapper, self.run_farm, hosts=all_run_farm_ips)

        @parallel
        def boot_simulation_wrapper(run_farm: RunFarm) -> None:
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node.instance_deploy_manager is not None
            my_node.instance_deploy_manager.start_simulations_instance()

        execute(boot_simulation_wrapper, self.run_farm, hosts=all_run_farm_ips)

    def kill_simulation_passes(self, use_mock_instances_for_testing: bool, disconnect_all_nbds: bool = True) -> None:
        """ Passes that kill the simulator. """
        self.run_farm.post_launch_binding(use_mock_instances_for_testing)

        all_run_farm_ips = [x.get_host() for x in self.run_farm.get_all_bound_host_nodes()]

        @parallel
        def kill_switch_wrapper(run_farm: RunFarm) -> None:
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node.instance_deploy_manager is not None
            my_node.instance_deploy_manager.kill_switches_instance()

        @parallel
        def kill_simulation_wrapper(run_farm: RunFarm) -> None:
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node.instance_deploy_manager is not None
            my_node.instance_deploy_manager.kill_simulations_instance(disconnect_all_nbds=disconnect_all_nbds)

        execute(kill_switch_wrapper, self.run_farm, hosts=all_run_farm_ips)
        execute(kill_simulation_wrapper, self.run_farm, hosts=all_run_farm_ips)

        def screens() -> None:
            """ poll on screens to make sure kill succeeded. """
            with warn_only():
                rootLogger.info("Confirming exit...")
                # keep checking screen until it reports that there are no screens left
                while True:
                    screenoutput = run("screen -ls")
                    # If AutoILA is enabled, use the following condition
                    if "2 Sockets in" in screenoutput and "hw_server" in screenoutput and "virtual_jtag" in screenoutput:
                        break
                    # If AutoILA is disabled, use the following condition
                    elif "No Sockets found" in screenoutput:
                        break
                    time.sleep(1)

        execute(screens, hosts=all_run_farm_ips)

    def run_workload_passes(self, use_mock_instances_for_testing: bool) -> None:
        """ extra passes needed to do runworkload. """
        self.run_farm.post_launch_binding(use_mock_instances_for_testing)

        all_run_farm_ips = [x.get_host() for x in self.run_farm.get_all_bound_host_nodes()]

        rootLogger.info("""Creating the directory: {}""".format(self.workload.job_results_dir))
        localcap = local("""mkdir -p {}""".format(self.workload.job_results_dir), capture=True)
        rootLogger.debug("[localhost] " + str(localcap))
        rootLogger.debug("[localhost] " + str(localcap.stderr))

        rootLogger.debug("""Creating the directory: {}""".format(self.workload.job_monitoring_dir))
        localcap = local("""mkdir -p {}""".format(self.workload.job_monitoring_dir), capture=True)
        rootLogger.debug("[localhost] " + str(localcap))
        rootLogger.debug("[localhost] " + str(localcap.stderr))

        # boot up as usual
        self.boot_simulation_passes(False, skip_instance_binding=True)

        @parallel
        def monitor_jobs_wrapper(
                run_farm: RunFarm,
                prior_completed_jobs: List[str],
                is_final_loop: bool,
                is_networked: bool,
                terminateoncompletion: bool,
                job_results_dir: str) -> Dict[str, Dict[str, bool]]:
            """ on each instance, check over its switches and simulations
            to copy results off. """
            my_node = run_farm.lookup_by_host(env.host_string)
            assert my_node.instance_deploy_manager is not None
            return my_node.instance_deploy_manager.monitor_jobs_instance(prior_completed_jobs, is_final_loop, is_networked, terminateoncompletion, job_results_dir)

        def loop_logger(instancestates: Dict[str, Any], terminateoncompletion: bool) -> None:
            """ Print the simulation status nicely. """

            instancestate_map = dict()
            if terminateoncompletion:
                for instip, instdata in instancestates.items():
                    # if terminateoncompletion and all sims are terminated, the inst must have been terminated
                    instancestate_map[instip] = all([x[1] for x in instdata['sims'].items()])
            else:
                instancestate_map = {inst: False for inst in instancestates.keys()}

            switchstates = []
            for instip, instdata in instancestates.items():
                for switchname, switchcompleted in instdata['switches'].items():
                    switchstates.append({'hostip': instip,
                                         'switchname': switchname,
                                         'running': not switchcompleted})

            simstates = []
            for instip, instdata in instancestates.items():
                for simname, simcompleted in instdata['sims'].items():
                    simstates.append({'hostip': instip,
                                         'simname': simname,
                                         'running': not simcompleted})


            truefalsecolor = [Fore.YELLOW + "False" + Style.RESET_ALL,
                                    Fore.GREEN + "True " + Style.RESET_ALL]
            inverttruefalsecolor = [Fore.GREEN + "False" + Style.RESET_ALL,
                                    Fore.YELLOW + "True " + Style.RESET_ALL]



            totalsims = len(simstates)
            totalinsts = len(instancestate_map.keys())
            runningsims = len([x for x in simstates if x['running']])
            runninginsts = len([x for x in instancestate_map.items() if not x[1]])

            longestinst = max([len(e) for e in instancestate_map.keys()], default=15)
            longestswitch = max([len(e['hostip']) for e in switchstates], default=15)
            longestsim = max([len(e['hostip']) for e in simstates], default=15)

            # clear the screen
            rootLogger.info('\033[2J')
            rootLogger.info("""FireSim Simulation Status @ {}""".format(str(datetime.datetime.utcnow())))
            rootLogger.info("-"*80)
            rootLogger.info("""This workload's output is located in:\n{}""".format(self.workload.job_results_dir))
            assert isinstance(rootLogger.handlers[0], logging.FileHandler)
            rootLogger.info("""This run's log is located in:\n{}""".format(rootLogger.handlers[0].baseFilename))
            rootLogger.info("""This status will update every 10s.""")
            rootLogger.info("-"*80)
            rootLogger.info("Instances")
            rootLogger.info("-"*80)
            for instance in instancestate_map.keys():
                rootLogger.info("""Hostname/IP: {:>{}} | Terminated: {}""".format(instance, longestinst, truefalsecolor[instancestate_map[instance]]))
            rootLogger.info("-"*80)
            rootLogger.info("Simulated Switches")
            rootLogger.info("-"*80)
            for switchinfo in switchstates:
                rootLogger.info("""Hostname/IP: {:>{}} | Switch name: {} | Switch running: {}""".format(switchinfo['hostip'], longestswitch, switchinfo['switchname'], truefalsecolor[switchinfo['running']]))
            rootLogger.info("-"*80)
            rootLogger.info("Simulated Nodes/Jobs")
            rootLogger.info("-"*80)
            for siminfo in simstates:
                rootLogger.info("""Hostname/IP: {:>{}} | Job: {} | Sim running: {}""".format(siminfo['hostip'], longestsim, siminfo['simname'], inverttruefalsecolor[siminfo['running']]))
            rootLogger.info("-"*80)
            rootLogger.info("Summary")
            rootLogger.info("-"*80)
            rootLogger.info("""{}/{} instances are still running.""".format(runninginsts, totalinsts))
            rootLogger.info("""{}/{} simulations are still running.""".format(runningsims, totalsims))
            rootLogger.info("-"*80)

        # is networked if a switch node is the root
        is_networked = isinstance(self.firesimtopol.roots[0], FireSimSwitchNode)

        # run polling loop
        while True:
            """ break out of this loop when either all sims are completed (no
            network) or when one sim is completed (networked case) """

            def get_jobs_completed_local_info():
                # this is a list of jobs completed, since any completed job will have
                # a directory within this directory.
                monitored_jobs_completed = os.listdir(self.workload.job_monitoring_dir)
                rootLogger.debug(f"Monitoring dir jobs completed: {monitored_jobs_completed}")
                return monitored_jobs_completed

            # return all the state about the instance (potentially copy back results and/or terminate)
            is_final_run = False
            monitored_jobs_completed = get_jobs_completed_local_info()
            instancestates = execute(monitor_jobs_wrapper,
                                     self.run_farm,
                                     monitored_jobs_completed,
                                     is_final_run,
                                     is_networked,
                                     self.terminateoncompletion,
                                     self.workload.job_results_dir,
                                     hosts=all_run_farm_ips)

            # log sim state, raw
            rootLogger.debug(pprint.pformat(instancestates))

            # log sim state, properly
            loop_logger(instancestates, self.terminateoncompletion)

            jobs_complete_dict = {}
            simstates = [x['sims'] for x in instancestates.values()]
            for x in simstates:
                jobs_complete_dict.update(x)
            global_status = jobs_complete_dict.values()
            rootLogger.debug(f"Jobs complete: {jobs_complete_dict}")
            rootLogger.debug(f"Global status: {global_status}")

            if is_networked and any(global_status):
                # at least one simulation has finished

                # in this case, do the teardown, then call exec again, then exit
                rootLogger.info("Networked simulation, manually tearing down all instances...")
                # do not disconnect nbds, because we may need them for copying
                # results. the process of copying results will tear them down anyway
                self.kill_simulation_passes(use_mock_instances_for_testing, disconnect_all_nbds=False)

                rootLogger.debug("One more loop to fully copy results and terminate.")
                is_final_run = True
                monitored_jobs_completed = get_jobs_completed_local_info()
                instancestates = execute(monitor_jobs_wrapper,
                                         self.run_farm,
                                         monitored_jobs_completed,
                                         is_final_run,
                                         is_networked,
                                         self.terminateoncompletion,
                                         self.workload.job_results_dir,
                                         hosts=all_run_farm_ips)
                break

            if not is_networked and all(global_status):
                break

            time.sleep(10)

        # run post-workload hook, if one exists
        if self.workload.post_run_hook is not None:
            rootLogger.info("Running post_run_hook...")
            localcap = local("""cd {} && {} {}""".format(self.workload.workload_input_base_dir,
                                                self.workload.post_run_hook,
                                                self.workload.job_results_dir),
                                                capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

        rootLogger.info("FireSim Simulation Exited Successfully. See results in:\n" + str(self.workload.job_results_dir))

if __name__ == "__main__":
    import doctest
    doctest.testmod()
