""" This constructs a topology and performs a series of passes on it. """

import time
import os
import pprint
import logging
import datetime

from switch_model_config import *
from firesim_topology_core import *
from utils import MacAddress
from fabric.api import *
from colorama import Fore, Style
import types

from util.streamlogger import StreamLogger

rootLogger = logging.getLogger()

@parallel
def instance_liveness():
    """ confirm that all instances are running first. """
    rootLogger.info("""[{}] Checking if host instance is up...""".format(env.host_string))
    with StreamLogger('stdout'), StreamLogger('stderr'):
        run("uname -a")

class FireSimTopologyWithPasses:
    """ This class constructs a FireSimTopology, then performs a series of passes
    on the topology to map it all the way to something usable to deploy a simulation.

    >>> tconf = FireSimTargetConfiguration("example_16config")
    """

    def __init__(self, user_topology_name, no_net_num_nodes, run_farm, hwdb,
                 defaulthwconfig, workload, defaultlinklatency, defaultswitchinglatency,
                 defaultnetbandwidth, defaultprofileinterval,
                 defaulttraceenable, defaulttracestart, defaulttraceend,
                 terminateoncompletion):
        self.passes_used = []
        self.user_topology_name = user_topology_name
        self.no_net_num_nodes = no_net_num_nodes
        self.run_farm = run_farm
        self.hwdb = hwdb
        self.workload = workload
        self.firesimtopol = FireSimTopology(user_topology_name, no_net_num_nodes)
        self.defaulthwconfig = defaulthwconfig
        self.defaultlinklatency = defaultlinklatency
        self.defaultswitchinglatency = defaultswitchinglatency
        self.defaultnetbandwidth = defaultnetbandwidth
        self.defaultprofileinterval = defaultprofileinterval
        self.defaulttraceenable = defaulttraceenable
        self.defaulttracestart = defaulttracestart
        self.defaulttraceend = defaulttraceend
        self.terminateoncompletion = terminateoncompletion

        self.phase_one_passes()

    def pass_return_dfs(self):
        """ Just return the nodes in DFS order """
        return self.firesimtopol.get_dfs_order()


    def pass_assign_mac_addresses(self):
        """ DFS through the topology to assign mac addresses """
        self.passes_used.append("pass_assign_mac_addresses")

        nodes_dfs_order = self.firesimtopol.get_dfs_order()
        MacAddress.reset_allocator()
        for node in nodes_dfs_order:
            if isinstance(node, FireSimServerNode):
                node.assign_mac_address(MacAddress())


    def pass_compute_switching_tables(self):
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
                childdownlinkmacs = [x.get_downlink_side().downlinkmacs for x in node.downlinks]
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

    def pass_create_topology_diagram(self):
        """ Produce a PDF that shows a diagram of the network.
        Useful for debugging passes to see what has been done to particular
        nodes. """
        from graphviz import Digraph

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
                downlink = downlink.get_downlink_side()
                gviz_graph.edge(str(node), str(downlink))

        gviz_graph.render(view=False)

    def pass_no_net_host_mapping(self):
        # only if we have no networks - pack simulations
        # assumes the user has provided enough or more slots
        servers = self.firesimtopol.get_dfs_order_servers()
        serverind = 0

        for f1_16x in self.run_farm.f1_16s:
            for x in range(f1_16x.get_num_fpga_slots_max()):
                f1_16x.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return
        for f1_2x in self.run_farm.f1_2s:
            for x in range(f1_2x.get_num_fpga_slots_max()):
                f1_2x.add_simulation(servers[serverind])
                serverind += 1
                if len(servers) == serverind:
                    return
        assert serverind == len(servers), "ERR: all servers were not assigned to a host."

    def pass_simple_networked_host_node_mapping(self):
        """ A very simple host mapping strategy.  """
        switches = self.firesimtopol.get_dfs_order_switches()
        f1_2s_used = 0
        f1_4s_used = 0
        f1_16s_used = 0
        m4_16s_used = 0

        for switch in switches:
            # Filter out FireSimDummyServerNodes for actually deploying.
            # Infrastructure after this point will automatically look at the
            # FireSimDummyServerNodes if a FireSimSuperNodeServerNode is used
            downlinknodes = map(lambda x: x.get_downlink_side(), [downlink for downlink in switch.downlinks if not isinstance(downlink.get_downlink_side(), FireSimDummyServerNode)])
            if all([isinstance(x, FireSimSwitchNode) for x in downlinknodes]):
                # all downlinks are switches
                self.run_farm.m4_16s[m4_16s_used].add_switch(switch)
                m4_16s_used += 1
            elif all([isinstance(x, FireSimServerNode) for x in downlinknodes]):
                # all downlinks are simulations
                if (len(downlinknodes) == 1) and (f1_2s_used < len(self.run_farm.f1_2s)):
                    self.run_farm.f1_2s[f1_2s_used].add_switch(switch)
                    self.run_farm.f1_2s[f1_2s_used].add_simulation(downlinknodes[0])
                    f1_2s_used += 1
                elif (len(downlinknodes) == 2) and (f1_4s_used < len(self.run_farm.f1_4s)):
                    self.run_farm.f1_4s[f1_4s_used].add_switch(switch)
                    for server in downlinknodes:
                        self.run_farm.f1_4s[f1_4s_used].add_simulation(server)
                    f1_4s_used += 1
                else:
                    self.run_farm.f1_16s[f1_16s_used].add_switch(switch)
                    for server in downlinknodes:
                        self.run_farm.f1_16s[f1_16s_used].add_simulation(server)
                    f1_16s_used += 1
            else:
                assert False, "Mixed downlinks currently not supported."""

    def mapping_use_one_f1_16xlarge(self):
        """ Just put everything on one f1.16xlarge """
        switches = self.firesimtopol.get_dfs_order_switches()
        f1_2s_used = 0
        f1_16s_used = 0
        m4_16s_used = 0

        for switch in switches:
            self.run_farm.f1_16s[f1_16s_used].add_switch(switch)
            downlinknodes = map(lambda x: x.get_downlink_side(), switch.downlinks)
            if all([isinstance(x, FireSimServerNode) for x in downlinknodes]):
                for server in downlinknodes:
                    self.run_farm.f1_16s[f1_16s_used].add_simulation(server)
            elif any([isinstance(x, FireSimServerNode) for x in downlinknodes]):
                assert False, "MIXED DOWNLINKS NOT SUPPORTED."
        f1_16s_used += 1

    def pass_perform_host_node_mapping(self):
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
                return
            else:
                # now, we're handling the cycle-accurate networked simulation case
                # currently, we only handle the case where
                self.pass_simple_networked_host_node_mapping()
        elif type(self.firesimtopol.custom_mapper) == types.FunctionType:
            """ call the mapper fn defined in the topology itself. """
            self.firesimtopol.custom_mapper(self)
        elif type(self.firesimtopol.custom_mapper) == str:
            """ assume that the mapping strategy is a custom pre-defined strategy
            given in this class, supplied as a string in the topology """
            mapperfunc = getattr(self, self.firesimtopol.custom_mapper)
            mapperfunc()
        else:
            assert False, "IMPROPER MAPPING CONFIGURATION"

    def pass_apply_default_hwconfig(self):
        """ This is the default mapping pass for hardware configurations - it
        does 3 things:
            1) If a node has a hardware config assigned (as a string), replace
            it with the appropriate RuntimeHWConfig object.
            2) If a node's hardware config is none, give it the default
            hardware config.
            3) In either case, call get_deploytriplet_for_config() once to
            make the API call and cache the result for the deploytriplet.
        """
        servers = self.firesimtopol.get_dfs_order_servers()
        defaulthwconfig_obj = self.hwdb.get_runtimehwconfig_from_name(self.defaulthwconfig)

        for server in servers:
            servhwconf = server.get_server_hardware_config()
            if servhwconf is None:
                # 2)
                server.set_server_hardware_config(defaulthwconfig_obj)
            else:
                # 1)
                server.set_server_hardware_config(self.hwdb.get_runtimehwconfig_from_name(servhwconf))
            # 3)
            server.get_server_hardware_config().get_deploytriplet_for_config()

    def pass_apply_default_network_params(self):
        """ If the user has not set per-node network parameters in the topology,
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
                if node.trace_enable is None:
                    node.trace_enable = self.defaulttraceenable
                if node.trace_start is None:
                    node.trace_start = self.defaulttracestart
                if node.trace_end is None:
                    node.trace_end = self.defaulttraceend

    def pass_assign_jobs(self):
        """ assign jobs to simulations. """
        servers = self.firesimtopol.get_dfs_order_servers()
        [servers[i].assign_job(self.workload.get_job(i)) for i in range(len(servers))]


    def phase_one_passes(self):
        """ These are passes that can run without requiring host-node binding.
        i.e. can be run before you have run launchrunfarm. They're run
        automatically when creating this object. """
        self.pass_assign_mac_addresses()
        self.pass_compute_switching_tables()
        self.pass_perform_host_node_mapping() # TODO: we can know ports here?
        self.pass_apply_default_hwconfig()
        self.pass_apply_default_network_params()
        self.pass_assign_jobs()

        self.pass_create_topology_diagram()

    def pass_build_required_drivers(self):
        """ Build all FPGA drivers. The method we're calling here won't actually
        repeat the build process more than once per run of the manager. """
        servers = self.firesimtopol.get_dfs_order_servers()

        for server in servers:
            server.get_server_hardware_config().build_fpga_driver()

    def pass_build_required_switches(self):
        """ Build all the switches required for this simulation. """
        # the way the switch models are designed, this requires hosts to be
        # bound to instances.
        switches = self.firesimtopol.get_dfs_order_switches()
        for switch in switches:
            switch.build_switch_sim_binary()


    def infrasetup_passes(self, use_mock_instances_for_testing):
        """ extra passes needed to do infrasetup """
        if use_mock_instances_for_testing:
            self.run_farm.bind_mock_instances_to_objects()
        else:
            self.run_farm.bind_real_instances_to_objects()
        self.pass_build_required_drivers()
        self.pass_build_required_switches()

        @parallel
        def infrasetup_node_wrapper(runfarm):
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            my_node.instance_deploy_manager.infrasetup_instance()

        all_runfarm_ips = [x.get_private_ip() for x in self.run_farm.get_all_host_nodes()]
        execute(instance_liveness, hosts=all_runfarm_ips)
        execute(infrasetup_node_wrapper, self.run_farm, hosts=all_runfarm_ips)

    def boot_simulation_passes(self, use_mock_instances_for_testing, skip_instance_binding=False):
        """ Passes that setup for boot and boot the simulation.
        skip instance binding lets users not call the binding pass on the run_farm
        again, e.g. if this was called by runworkload (because runworkload calls
        boot_simulation_passes internally)
        TODO: the reason we need this is that somehow we're getting
        garbage results if the AWS EC2 API gets called twice by accident
        (e.g.  incorrect private IPs)
        """
        if not skip_instance_binding:
            if use_mock_instances_for_testing:
                self.run_farm.bind_mock_instances_to_objects()
            else:
                self.run_farm.bind_real_instances_to_objects()

        @parallel
        def boot_switch_wrapper(runfarm):
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            my_node.instance_deploy_manager.start_switches_instance()

        all_runfarm_ips = [x.get_private_ip() for x in self.run_farm.get_all_host_nodes()]
        execute(instance_liveness, hosts=all_runfarm_ips)
        execute(boot_switch_wrapper, self.run_farm, hosts=all_runfarm_ips)

        @parallel
        def boot_simulation_wrapper(runfarm):
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            my_node.instance_deploy_manager.start_simulations_instance()

        execute(boot_simulation_wrapper, self.run_farm, hosts=all_runfarm_ips)

    def kill_simulation_passes(self, use_mock_instances_for_testing):
        """ Passes that kill the simulator. """
        if use_mock_instances_for_testing:
            self.run_farm.bind_mock_instances_to_objects()
        else:
            self.run_farm.bind_real_instances_to_objects()

        all_runfarm_ips = [x.get_private_ip() for x in self.run_farm.get_all_host_nodes()]

        @parallel
        def kill_switch_wrapper(runfarm):
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            my_node.instance_deploy_manager.kill_switches_instance()

        @parallel
        def kill_simulation_wrapper(runfarm):
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            my_node.instance_deploy_manager.kill_simulations_instance()

        execute(kill_switch_wrapper, self.run_farm, hosts=all_runfarm_ips)
        execute(kill_simulation_wrapper, self.run_farm, hosts=all_runfarm_ips)

        def screens():
            """ poll on screens to make sure kill succeeded. """
            with warn_only():
                rootLogger.info("Confirming exit...")
                # keep checking screen until it reports that there are no screens left
                while True:
                    with StreamLogger('stdout'), StreamLogger('stderr'):
                        screenoutput = run("screen -ls")
                        # If AutoILA is enabled, use the following condition
                        if "2 Sockets in" in screenoutput and "hw_server" in screenoutput and "virtual_jtag" in screenoutput:
                            break
                        # If AutoILA is disabled, use the following condition
                        elif "No Sockets found" in screenoutput:
                            break 
                        time.sleep(1)

        execute(screens, hosts=all_runfarm_ips)

    def run_workload_passes(self, use_mock_instances_for_testing):
        """ extra passes needed to do runworkload. """
        if use_mock_instances_for_testing:
            self.run_farm.bind_mock_instances_to_objects()
        else:
            self.run_farm.bind_real_instances_to_objects()

        all_runfarm_ips = [x.get_private_ip() for x in self.run_farm.get_all_host_nodes()]

        rootLogger.info("""Creating the directory: {}""".format(self.workload.job_results_dir))
        with StreamLogger('stdout'), StreamLogger('stderr'):
            localcap = local("""mkdir -p {}""".format(self.workload.job_results_dir), capture=True)
            rootLogger.debug("[localhost] " + str(localcap))
            rootLogger.debug("[localhost] " + str(localcap.stderr))

        # boot up as usual
        self.boot_simulation_passes(False, skip_instance_binding=True)

        @parallel
        def monitor_jobs_wrapper(runfarm, completed_jobs, teardown, terminateoncompletion, job_results_dir):
            """ on each instance, check over its switches and simulations
            to copy results off. """
            my_node = runfarm.lookup_by_ip_addr(env.host_string)
            return my_node.instance_deploy_manager.monitor_jobs_instance(completed_jobs, teardown, terminateoncompletion, job_results_dir)


        def loop_logger(instancestates, terminateoncompletion):
            """ Print the simulation status nicely. """

            instancestate_map = dict()
            if terminateoncompletion:
                for instip, instdata in instancestates.iteritems():
                    # if terminateoncompletion and all sims are terminated, the inst must have been terminated
                    instancestate_map[instip] = all([x[1] for x in instdata['sims'].iteritems()])
            else:
                instancestate_map = {inst: False for inst in instancestates.keys()}

            switchstates = []
            for instip, instdata in instancestates.iteritems():
                for switchname, switchcompleted in instdata['switches'].iteritems():
                    switchstates.append({'hostip': instip,
                                         'switchname': switchname,
                                         'running': not switchcompleted})

            simstates = []
            for instip, instdata in instancestates.iteritems():
                for simname, simcompleted in instdata['sims'].iteritems():
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
            runninginsts = len([x for x in instancestate_map.iteritems() if not x[1]])

            # clear the screen
            rootLogger.info('\033[2J')
            rootLogger.info("""FireSim Simulation Status @ {}""".format(str(datetime.datetime.utcnow())))
            rootLogger.info("-"*80)
            rootLogger.info("""This workload's output is located in:\n{}""".format(self.workload.job_results_dir))
            rootLogger.info("""This run's log is located in:\n{}""".format(rootLogger.handlers[0].baseFilename))
            rootLogger.info("""This status will update every 10s.""")
            rootLogger.info("-"*80)
            rootLogger.info("Instances")
            rootLogger.info("-"*80)
            for instance in instancestate_map.keys():
                rootLogger.info("""Instance IP:{:>15} | Terminated: {}""".format(instance, truefalsecolor[instancestate_map[instance]]))
            rootLogger.info("-"*80)
            rootLogger.info("Simulated Switches")
            rootLogger.info("-"*80)
            for switchinfo in switchstates:
                rootLogger.info("""Instance IP:{:>15} | Switch name: {} | Switch running: {}""".format(switchinfo['hostip'], switchinfo['switchname'], truefalsecolor[switchinfo['running']]))
            rootLogger.info("-"*80)
            rootLogger.info("Simulated Nodes/Jobs")
            rootLogger.info("-"*80)
            for siminfo in simstates:
                rootLogger.info("""Instance IP:{:>15} | Job: {} | Sim running: {}""".format(siminfo['hostip'], siminfo['simname'], inverttruefalsecolor[siminfo['running']]))
            rootLogger.info("-"*80)
            rootLogger.info("Summary")
            rootLogger.info("-"*80)
            rootLogger.info("""{}/{} instances are still running.""".format(runninginsts, totalinsts))
            rootLogger.info("""{}/{} simulations are still running.""".format(runningsims, totalsims))
            rootLogger.info("-"*80)

        # teardown is required if roots are switches
        teardown_required = isinstance(self.firesimtopol.roots[0], FireSimSwitchNode)

        # run polling loop
        while True:
            """ break out of this loop when either all sims are completed (no
            network) or when one sim is completed (networked case) """

            # this is a list of jobs completed, since any completed job will have
            # a directory within this directory.
            jobscompleted = os.listdir(self.workload.job_results_dir)
            rootLogger.debug(jobscompleted)

            # figure out what the teardown condition is: for now we handle just
            # single-rooted cycle-accurate network,
            # no network
            do_teardown = len(jobscompleted) == len(self.firesimtopol.roots)

            # this job on the instance should return all the state about the instance
            # e.g.:
            # if an instance has been terminated (really - is termination
            # requested and no jobs are left, then we will have implicitly
            # terminated
            teardown = False
            instancestates = execute(monitor_jobs_wrapper, self.run_farm,
                                    jobscompleted, teardown,
                                    self.terminateoncompletion,
                                    self.workload.job_results_dir,
                                    hosts=all_runfarm_ips)

            # log sim state, raw
            rootLogger.debug(pprint.pformat(instancestates))

            # log sim state, properly
            loop_logger(instancestates, self.terminateoncompletion)

            jobs_complete_dict = dict()
            simstates = [x['sims'] for x in instancestates.values()]
            global_status = [jobs_complete_dict.update(x) for x in simstates]
            global_status = jobs_complete_dict.values()
            rootLogger.debug("jobs complete dict " + str(jobs_complete_dict))
            rootLogger.debug("global status: " + str(global_status))

            if teardown_required and any(global_status):
                # in this case, do the teardown, then call exec again, then exit
                rootLogger.info("Teardown required, manually tearing down...")
                self.kill_simulation_passes(use_mock_instances_for_testing)
                rootLogger.debug("continuing one more loop to fully copy results and terminate")
                teardown = True
                instancestates = execute(monitor_jobs_wrapper, self.run_farm,
                                        jobscompleted, teardown,
                                        self.terminateoncompletion,
                                        self.workload.job_results_dir,
                                        hosts=all_runfarm_ips)
                break
            if not teardown_required and all(global_status):
                break

            time.sleep(10)

        # run post-workload hook, if one exists
        if self.workload.post_run_hook is not None:
            with StreamLogger('stdout'), StreamLogger('stderr'):
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
