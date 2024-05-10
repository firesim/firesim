from __future__ import annotations

from dataclasses import dataclass

from typing import Dict, Any

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

@dataclass
class PartitionConfig:
    partitioned: bool
    batch_size: int
    is_base: bool
    pidx: int
    fpga_cnt: int

    def __init__(self,
                 partitioned: bool = False,
                 batch_size: int = 0,
                 base: bool = True,
                 pidx: int = 0,
                 fpga_cnt: int = 1) -> None:
        self.partitioned = partitioned
        self.batch_size = batch_size
        self.is_base = base
        self.pidx = pidx
        self.fpga_cnt = fpga_cnt

    def mac_address_assignable(self) -> bool:
        return (not self.partitioned) or (self.partitioned and self.is_base)

    def leaf_partition(self) -> bool:
        return self.partitioned and (not self.is_base)
