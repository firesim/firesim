from __future__ import annotations

from dataclasses import dataclass

from typing import Dict, Any, List, Optional, Tuple
from enum import Enum


@dataclass
class TracerVConfig:
    enable: bool
    select: str
    start: str
    end: str
    output_format: str

    def __init__(self, args: Dict[str, Any]) -> None:
        self.enable = args.get("enable", False) == True
        self.select = args.get("selector", "0")
        self.start = args.get("start", "0")
        self.end = args.get("end", "-1")
        self.output_format = args.get("output_format", "0")


@dataclass
class AutoCounterConfig:
    readrate: int

    def __init__(self, args: Dict[str, Any]) -> None:
        self.readrate = int(args.get("read_rate", "0"))


@dataclass
class HostDebugConfig:
    zero_out_dram: bool
    disable_synth_asserts: bool

    def __init__(self, args: Dict[str, Any]) -> None:
        self.zero_out_dram = args.get("zero_out_dram", False) == True
        self.disable_synth_asserts = args.get("disable_synth_asserts", False) == True


@dataclass
class SynthPrintConfig:
    start: str
    end: str
    cycle_prefix: bool

    def __init__(self, args: Dict[str, Any]) -> None:
        self.start = args.get("start", "0")
        self.end = args.get("end", "-1")
        self.cycle_prefix = args.get("cycle_prefix", True) == True


class FireAxeNodeBridgePair:
    """
    pidx : partition index of the node
    bidx : bridge index
    """

    pidx: int
    bidx: int

    def __init__(self, pidx: int, bidx: int) -> None:
        self.pidx = pidx
        self.bidx = bidx


class FireAxeEdge:
    """
    Connects two `FireAxeNodeBridgePair`s u, v
    """

    u: FireAxeNodeBridgePair
    v: FireAxeNodeBridgePair

    def __init__(self, u: FireAxeNodeBridgePair, v: FireAxeNodeBridgePair) -> None:
        self.u = u
        self.v = v


class PartitionMode(Enum):
    FAST_MODE = 0
    EXACT_MODE = 1
    NOC_MODE = 2


class PartitionNode:
    """
    Partitioning information for a single FireSimServerNode
    hwdb  : FireSimHWDB
    pidx  : partition index
    edges : my_bridge -> (neighbor_bridge, neighbor_node)
    """

    hwdb: str
    pidx: int
    edges: Dict[int, Tuple[int, PartitionNode]]

    def __init__(self, hwdb: str, pidx: int) -> None:
        self.hwdb = hwdb
        self.pidx = pidx
        self.edges = dict()

    def sort_edges_by_bridge_idx(self) -> None:
        self.edges = dict(sorted(self.edges.items()))

    def add_edge(self, bidx: int, nbidx: int, node: PartitionNode) -> None:
        self.edges[bidx] = (nbidx, node)
        self.sort_edges_by_bridge_idx()


@dataclass
class PartitionConfig:
    """
    Provides information to a FireSimServerNode about the global partitioning topology
    """

    node: Optional[PartitionNode]
    fpga_cnt: int
    pidx_to_slotid: Dict[int, int]
    pcim_slot_offset: List[Tuple[int, int]]  # slotid, bridge offset of neighbor
    mode: PartitionMode

    def __init__(
        self,
        node: Optional[PartitionNode] = None,
        pidx_to_slotid: Dict[int, int] = dict(),
        mode: PartitionMode = PartitionMode.FAST_MODE,
    ) -> None:
        self.node = node
        self.fpga_cnt = max(len(pidx_to_slotid.keys()), 1)
        self.pidx_to_slotid = pidx_to_slotid
        self.pcim_slot_offset = list()
        self.mode = mode

    def get_hwdb(self) -> str:
        assert self.node is not None
        return self.node.hwdb

    def get_edges(self) -> Dict[int, Tuple[int, PartitionNode]]:
        assert self.node is not None
        return self.node.edges

    def add_pcim_slot_offset(self, slot: int, offset: int) -> None:
        self.pcim_slot_offset.append((slot, offset))

    def is_base(self) -> bool:
        if self.node is None:
            return True
        else:
            return self.node.pidx == (self.fpga_cnt - 1)

    def is_partitioned(self) -> bool:
        return self.fpga_cnt > 1

    def batch_size(self) -> int:
        if self.mode == PartitionMode.FAST_MODE:
            return 1
        elif (self.mode == PartitionMode.EXACT_MODE) or (
            self.mode == PartitionMode.NOC_MODE
        ):
            return 0
        else:
            print(f"Unrecognized partition mode {self.mode}")
            exit(1)

    def metasim_partition_topo_args(self) -> int:
        if (
            (self.mode == PartitionMode.FAST_MODE)
            or (self.mode == PartitionMode.EXACT_MODE)
            or (self.mode == PartitionMode.NOC_MODE)
        ):
            return self.mode.value
        else:
            print("Unrecognized topology")
            exit(1)

    def mac_address_assignable(self) -> bool:
        return (not self.is_partitioned()) or (self.is_partitioned() and self.is_base())

    def leaf_partition(self) -> bool:
        return self.is_partitioned() and (not self.is_base())

    def get_pcim_slot_and_bridge_offsets(self) -> List[str]:
        return [
            f"{slotid},{bridgeoffset}"
            for (slotid, bridgeoffset) in self.pcim_slot_offset
        ]
