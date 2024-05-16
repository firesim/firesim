from __future__ import annotations

from dataclasses import dataclass

from typing import Dict, Any, List
from enum import Enum

@dataclass
class TracerVConfig:
    enable: bool
    select: str
    start: str
    end: str
    output_format: str

    def __init__(self, args: Dict[str, Any]) -> None:
        self.enable = args.get('enable', False) == True
        self.select = args.get('selector', "0")
        self.start = args.get('start', "0")
        self.end = args.get('end', "-1")
        self.output_format = args.get('output_format', "0")

@dataclass
class AutoCounterConfig:
    readrate: int

    def __init__(self, args: Dict[str, Any]) -> None:
        self.readrate = int(args.get('read_rate', "0"))

@dataclass
class HostDebugConfig:
    zero_out_dram: bool
    disable_synth_asserts: bool

    def __init__(self, args: Dict[str, Any]) -> None:
        self.zero_out_dram = args.get('zero_out_dram', False) == True
        self.disable_synth_asserts = args.get('disable_synth_asserts', False) == True

@dataclass
class SynthPrintConfig:
    start: str
    end: str
    cycle_prefix: bool

    def __init__(self, args: Dict[str, Any]) -> None:
        self.start = args.get("start", "0")
        self.end = args.get("end", "-1")
        self.cycle_prefix = args.get("cycle_prefix", True) == True

class PartitionMode(Enum):
  FAST_MODE     = 1
  EXACT_MODE    = 2
  NOC_PARTITION = 3

@dataclass
class PartitionConfig:
    partitioned: bool
    fpga_cnt: int
    base: bool
    pidx: int
    batch_size: int
    fpga_topo: str

    slot0_bar4: str
    slot0_offset: List[str]
    slot1_bar4: str
    slot1_offset: List[str]

    def __init__(self,
                 partitioned: bool = False,
                 fpga_cnt: int = 1,
                 base: bool = True,
                 pidx: int = 0,
                 mode: PartitionMode = PartitionMode.FAST_MODE) -> None:
        self.partitioned = partitioned
        self.fpga_cnt = fpga_cnt
        self.base = base
        self.pidx = pidx
        if mode == PartitionMode.FAST_MODE:
          self.batch_size = 1
          self.fpga_topo = 'fast_mode'
        elif mode == PartitionMode.EXACT_MODE:
          self.batch_size = 0
          self.fpga_topo = 'exact_mode'
        elif mode == PartitionMode.NOC_PARTITION:
          self.batch_size = 0
          self.fpga_topo = 'noc_mode'
        else:
          print(f'Unrecognized partition mode {mode}')
          exit(1)

        self.slot0_bar4 = "0x70000000000"
        self.slot0_offset = ["0x0000"]
        self.slot1_bar4 =  "0x78000000000"
        self.slot1_offset =  ["0x0000"]

    def mac_address_assignable(self) -> bool:
        return (not self.partitioned) or (self.partitioned and self.base)

    def leaf_partition(self) -> bool:
        return self.partitioned and (not self.base)
