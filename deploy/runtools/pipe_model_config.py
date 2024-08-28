""" This file contains components that tie closely with the FireSim pipe
models that live in target-design/partition/ """

from __future__ import annotations

import random
import string
import logging
import os

from numpy import partition
from fabric.api import local  # type: ignore

from typing import List, Set, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from runtools.firesim_topology_elements import FireSimPipeNode, FireSimServerNode
    from runtools.runtime_config import RuntimeHWConfig

rootLogger = logging.getLogger()


GENERATED_PARTITION_PARAMS_FILE = "FireSim-generated.partition.const.h"


class PartitionBoundaryParams:
    _from_host: int
    _to_host: int
    _local_idx: int
    _pipe_idx: int

    def __init__(
        self, to_host: int, from_host: int, local_idx: int, pipe_idx: int
    ) -> None:
        self._to_host = to_host
        self._from_host = from_host
        self._local_idx = local_idx
        self._pipe_idx = pipe_idx

    def to_host(self) -> int:
        return self._to_host

    def from_host(self) -> int:
        return self._from_host

    def local_idx(self) -> int:
        return self._local_idx

    def global_idx(self) -> int:
        return 2 * self._pipe_idx + self._local_idx


class AbstractPipeToPipeConfig:
    """This class is responsible for providing functions that take a FireSimPipeNode
    and emit the correct config header to produce an actual switch simulator binary
    that behaves as defined in the FireSimPipeNode.

    This assumes that the switch has already been assigned to a host."""

    fsimpipenode: FireSimPipeNode
    build_disambiguate: str
    server_boundary_widths: List[PartitionBoundaryParams]
    server_cutbridge_idx_map: Dict[FireSimServerNode, int]

    def __init__(self, fsimpipenode: FireSimPipeNode) -> None:
        """Construct the pipe's config file"""
        self.fsimpipenode = fsimpipenode
        # this lets us run many builds in parallel without conflict across
        # parallel experiments which may have overlapping pipe ids
        self.build_disambiguate = "".join(
            random.choice(string.ascii_uppercase + string.digits) for _ in range(64)
        )
        self.server_boundary_widths = []
        self.server_cutbridge_idx_map = {}

    # FIXME: get_local_driver_dir returns ../output in f1 while it returns ../sim/generated-src in local metasims
    # Just set it to user ../sim/generated-src for now
    def partition_config_file(self, hwconfig: RuntimeHWConfig) -> str:
        runtime_conf_path = os.path.join(
            "../sim/generated-src",
            hwconfig.get_platform(),
            hwconfig.get_deployquintuplet_for_config(),
        )
        rootLogger.info(f"runtime_conf_path {runtime_conf_path}")
        return os.path.join(runtime_conf_path, GENERATED_PARTITION_PARAMS_FILE)

    def parse_partition_config_file(self, f: str) -> List[PartitionBoundaryParams]:
        from_host = 0
        to_host = 0
        with open(f, "r") as cfg:
            lines = cfg.readlines()
            for line in lines:
                words = line.split()
                if len(words) != 3:
                    continue
                if "FROM" in words[1]:
                    from_host = int(words[2])
                elif "TO" in words[1]:
                    to_host = int(words[2])
        assert from_host > 0, "zero dma transactions going from target to host"
        assert to_host > 0, "zero dma transactions going from host to target"

        leaf_from_host = from_host
        leaf_to_host = to_host

        base_from_host = to_host
        base_to_host = from_host

        pipe_idx = self.fsimpipenode.pipe_id_internal

        params = [
            PartitionBoundaryParams(
                base_to_host, base_from_host, 0, pipe_idx
            ),  # base server
            PartitionBoundaryParams(
                leaf_to_host, leaf_from_host, 1, pipe_idx
            ),  # leaf server
        ]
        return params

    def collect_partition_boundary_params(self) -> None:
        for server in self.fsimpipenode.partition_edge:
            rootLogger.debug(
                f"topology, {server}:{server.server_id_internal} {server.is_leaf_partition()}"
            )
            assert (
                server.is_partition()
            ), f"{server} is not a partitioned server although it is connected to a partition pipe"
            if server.is_leaf_partition():
                rootLogger.info(
                    f"server_hwdb {server.get_resolved_server_hardware_config()}"
                )
                pcfg_file = self.partition_config_file(
                    server.get_resolved_server_hardware_config()
                )
                self.server_boundary_widths = self.parse_partition_config_file(
                    pcfg_file
                )

        for server in self.fsimpipenode.partition_edge:
            boundary_param = (
                self.server_boundary_widths[1]
                if server.is_leaf_partition()
                else self.server_boundary_widths[0]
            )
            self.server_cutbridge_idx_map[server] = boundary_param.global_idx()
            rootLogger.info(
                f"collect_partition_boundary_params {server} {server.server_id_internal} {boundary_param.global_idx()}"
            )

    def emit_pipe_configfile(self) -> str:
        """Produce a config file for the pipe generator for this pipe"""
        constructedstring = ""
        constructedstring += self.get_header()
        constructedstring += self.get_partitions_config()
        constructedstring += self.get_pipesetup()
        return constructedstring

    def get_header(self) -> str:
        """Produce file header."""
        retstr = """// THIS FILE IS MACHINE GENERATED. SEE deploy/buildtools/pipe_model_config.py
        """
        return retstr

    def get_partitions_config(self) -> str:
        self.collect_partition_boundary_params()
        numpipes = len(self.server_boundary_widths)
        assert numpipes == 2, "Currently a pipe connects two cut boundaries"

        retstr = """
    #ifdef NUMPARTITIONSCONFIG
    #define NUMPIPES {}
        """.format(
            numpipes
        )

        retstr += """
    int TOHOST_DMATOKENS_PER_TRANSACTION[] = {
        """
        for p in self.server_boundary_widths[:-1]:
            retstr += "{},{}".format(p.to_host(), " ")
        retstr += """{}
        }};""".format(
            self.server_boundary_widths[-1].to_host()
        )

        retstr += """
    int FROMHOST_DMATOKENS_PER_TRANSACTION[] = {
        """
        for p in self.server_boundary_widths[:-1]:
            retstr += "{},{}".format(p.from_host(), " ")
        retstr += """{}
        }};""".format(
            self.server_boundary_widths[-1].from_host()
        )

        retstr += """
    int DESTINATION_PIPE_IDX[] = {1, 0};
        """

        retstr += """
    #endif
        """
        return retstr

    def get_pipesetup(self) -> str:
        initstring = ""
        for p in self.server_boundary_widths:
            initstring += (
                "pipes["
                + str(p.local_idx())
                + "] = "
                + "new ShmemPipe("
                + str(p.global_idx())
                + ", "
                + str(p.from_host())
                + ", "
                + str(p.to_host())
                + ");\n"
            )

        retstr = """
    #ifdef PIPESETUPCONFIG
    {}
    #endif
    """.format(
            initstring
        )
        return retstr

    def pipe_binary_name(self) -> str:
        return "pipe" + str(self.fsimpipenode.pipe_id_internal)

    def buildpipe(self) -> None:
        """Generate the config file, build the pipe."""

        configfile = self.emit_pipe_configfile()
        binaryname = self.pipe_binary_name()

        pipeorigdir = self.pipe_build_local_dir()
        pipebuilddir = (
            pipeorigdir + binaryname + "-" + self.build_disambiguate + "-build/"
        )

        rootLogger.info(
            "Building pipe model binary for pipe " + str(self.pipe_binary_name())
        )

        rootLogger.debug(str(configfile))

        def local_logged(command: str) -> None:
            """Run local command with logging."""
            localcap = local(command, capture=True)
            rootLogger.debug(localcap)
            rootLogger.debug(localcap.stderr)

        # make a build dir for this pipe
        local_logged("mkdir -p " + pipebuilddir)
        local_logged("cp " + pipeorigdir + "*.h " + pipebuilddir)
        local_logged("cp " + pipeorigdir + "*.cc " + pipebuilddir)
        local_logged("cp " + pipeorigdir + "Makefile " + pipebuilddir)

        text_file = open(pipebuilddir + "partitionconfig.h", "w")
        text_file.write(configfile)
        text_file.close()
        local_logged("cd " + pipebuilddir + " && make")
        local_logged(
            "mv " + pipebuilddir + "partitionpipe " + pipebuilddir + binaryname
        )

    def get_pipe_simulation_command(self, sudo: bool) -> str:
        """Return the command to boot the pipe."""
        # insert gdb -ex run --args between sudo and ./ below to start pipees in gdb

        partition_config = self.fsimpipenode.partition_config
        assert partition_config is not None
        batch_size = partition_config.batch_size
        return """screen -S {} -d -m bash -c "script -f -c '{} ./{} {}' pipelog"; sleep 1""".format(
            self.pipe_binary_name(),
            "sudo" if sudo else "",
            self.pipe_binary_name(),
            batch_size,
        )

    def kill_pipe_simulation_command(self) -> str:
        """Return the command to kill the pipe."""
        return """pkill -f -SIGKILL {}""".format(self.pipe_binary_name())

    def pipe_build_local_dir(self) -> str:
        """get local build dir of the pipe."""
        return "../target-design/partition/"

    def pipe_binary_local_path(self) -> str:
        """return the full local path where the pipe binary lives."""
        binaryname = self.pipe_binary_name()
        pipeorigdir = self.pipe_build_local_dir()
        pipebuilddir = (
            pipeorigdir + binaryname + "-" + self.build_disambiguate + "-build/"
        )
        return pipebuilddir + self.pipe_binary_name()
