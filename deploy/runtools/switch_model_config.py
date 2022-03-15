""" This file contains components that tie closely with the FireSim switch
models that live in target-design/switch/ """

import subprocess
import random
import string
import logging

from fabric.api import local
from util.streamlogger import StreamLogger

rootLogger = logging.getLogger()

class AbstractSwitchToSwitchConfig:
    """ This class is responsible for providing functions that take a FireSimSwitchNode
    and emit the correct config header to produce an actual switch simulator binary
    that behaves as defined in the FireSimSwitchNode.

    This assumes that the switch has already been assigned to a host."""

    def __init__(self, fsimswitchnode):
        """ Construct the switch's config file """
        self.fsimswitchnode = fsimswitchnode
        # this lets us run many builds in parallel without conflict across
        # parallel experiments which may have overlapping switch ids
        self.build_disambiguate = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(64))

    def emit_init_for_uplink(self, uplinkno):
        """ Emit an init for a switch to talk to it's uplink."""

        linkobj = self.fsimswitchnode.uplinks[uplinkno]
        upperswitch = linkobj.get_uplink_side()

        target_local_portno = len(self.fsimswitchnode.downlinks) + uplinkno
        if linkobj.link_crosses_hosts():
            uplinkhostip = linkobj.link_hostserver_ip() #upperswitch.host_instance.get_private_ip()
            uplinkhostport = linkobj.link_hostserver_port()

            return "new SocketClientPort(" + str(target_local_portno) +  \
                    ", \"" + uplinkhostip + "\", " + str(uplinkhostport) + ");\n"

        else:
            linkbasename = linkobj.get_global_link_id()
            return "new ShmemPort(" + str(target_local_portno) + ', "' + linkbasename + '", true);\n'

    def emit_init_for_downlink(self, downlinkno):
        """ emit an init for the specified downlink. """
        downlinkobj = self.fsimswitchnode.downlinks[downlinkno]
        downlink = downlinkobj.get_downlink_side()
        if downlinkobj.link_crosses_hosts():
            hostport = downlinkobj.link_hostserver_port()
            # create a SocketServerPort
            return "new SocketServerPort(" + str(downlinkno) + ", " + \
                    str(hostport)  + ");\n"
        else:
            linkbasename = downlinkobj.get_global_link_id()
            return "new ShmemPort(" + str(downlinkno) + ', "' + linkbasename + '", false);\n'

    def emit_switch_configfile(self):
        """ Produce a config file for the switch generator for this switch """
        constructedstring = ""
        constructedstring += self.get_header()
        constructedstring += self.get_numclientsconfig()
        constructedstring += self.get_portsetup()
        constructedstring += self.get_mac2port()
        return constructedstring

    # produce mac2port array portion of config
    def get_mac2port(self):
        """ This takes a python array that represents the mac to port mapping,
        and converts it to a C++ array """

        mac2port_pythonarray = self.fsimswitchnode.switch_table

        commaseparated = ""
        for elem in mac2port_pythonarray:
            commaseparated += str(elem) + ", "

        #remove extraneous ", "
        commaseparated = commaseparated[:-2]
        commaseparated = "{" + commaseparated + "};"

        retstr = """
    #ifdef MACPORTSCONFIG
    uint16_t mac2port[{}]  {}
    #endif
    """.format(len(mac2port_pythonarray), commaseparated)
        return retstr

    def get_header(self):
        """ Produce file header. """
        retstr = """// THIS FILE IS MACHINE GENERATED. SEE deploy/buildtools/switchmodelconfig.py
        """
        return retstr

    def get_numclientsconfig(self):
        """ Emit constants for num ports. """
        numdownlinks = len(self.fsimswitchnode.downlinks)
        numuplinks = len(self.fsimswitchnode.uplinks)
        totalports = numdownlinks + numuplinks

        retstr = """
    #ifdef NUMCLIENTSCONFIG
    #define NUMPORTS {}
    #define NUMDOWNLINKS {}
    #define NUMUPLINKS {}
    #endif""".format(totalports, numdownlinks, numuplinks)
        return retstr

    def get_portsetup(self):
        """ emit port intialisations. """
        initstring = ""
        for downlinkno in range(len(self.fsimswitchnode.downlinks)):
            initstring += "ports[" + str(downlinkno) + "] = " + \
                    self.emit_init_for_downlink(downlinkno)

        for uplinkno in range(len(self.fsimswitchnode.uplinks)):
            initstring += "ports[" + str(len(self.fsimswitchnode.downlinks) + \
                        uplinkno) + "] = " + self.emit_init_for_uplink(uplinkno)

        retstr = """
    #ifdef PORTSETUPCONFIG
    {}
    #endif
    """.format(initstring)
        return retstr

    def switch_binary_name(self):
        return "switch" + str(self.fsimswitchnode.switch_id_internal)

    def buildswitch(self):
        """ Generate the config file, build the switch.

        TODO: replace calls to subprocess.check_call here with fabric."""

        configfile = self.emit_switch_configfile()
        binaryname = self.switch_binary_name()

        switchorigdir = self.switch_build_local_dir()
        switchbuilddir = switchorigdir + binaryname + "-" + self.build_disambiguate + "-build/"

        rootLogger.info("Building switch model binary for switch " + str(self.switch_binary_name()))

        rootLogger.debug(str(configfile))

        def local_logged(command):
            """ Run local command with logging. """
            with StreamLogger('stdout'), StreamLogger('stderr'):
                localcap = local(command, capture=True)
                rootLogger.debug(localcap)
                rootLogger.debug(localcap.stderr)

        # make a build dir for this switch
        local_logged("mkdir -p " + switchbuilddir)
        local_logged("cp " + switchorigdir + "*.h " + switchbuilddir)
        local_logged("cp " + switchorigdir + "*.cc " + switchbuilddir)
        local_logged("cp " + switchorigdir + "Makefile " + switchbuilddir)

        text_file = open(switchbuilddir + "switchconfig.h", "w")
        text_file.write(configfile)
        text_file.close()
        local_logged("cd " + switchbuilddir + " && make")
        local_logged("mv " + switchbuilddir + "switch " + switchbuilddir + binaryname)

    def run_switch_simulation_command(self):
        """ Return the command to boot the switch."""
        switchlatency = self.fsimswitchnode.switch_switching_latency
        linklatency = self.fsimswitchnode.switch_link_latency
        bandwidth = self.fsimswitchnode.switch_bandwidth
        # insert gdb -ex run --args between sudo and ./ below to start switches in gdb
        return """screen -S {} -d -m bash -c "script -f -c 'sudo ./{} {} {} {}' switchlog"; sleep 1""".format(self.switch_binary_name(), self.switch_binary_name(), linklatency, switchlatency, bandwidth)

    def kill_switch_simulation_command(self):
        """ Return the command to kill the switch. """
        return """sudo pkill {}""".format(self.switch_binary_name())

    def switch_build_local_dir(self):
        """ get local build dir of the switch. """
        return "../target-design/switch/"

    def switch_binary_local_path(self):
        """ return the full local path where the switch binary lives. """
        binaryname = self.switch_binary_name()
        switchorigdir = self.switch_build_local_dir()
        switchbuilddir = switchorigdir + binaryname + "-" + self.build_disambiguate + "-build/"
        return switchbuilddir + self.switch_binary_name()
