from __future__ import annotations

from dataclasses import dataclass

@dataclass
class TracerVConfig:
    enable: bool = False
    select: str = "0"
    start: str = "0"
    end: str = "-1"
    output_format: str = "0"

@dataclass
class AutoCounterConfig:
    readrate: int = 0

@dataclass
class HostDebugConfig:
    zero_out_dram: bool = False
    disable_synth_asserts: bool = False

@dataclass
class SynthPrintConfig:
    start: str = "0"
    end: str = "-1"
    cycle_prefix: bool = True
