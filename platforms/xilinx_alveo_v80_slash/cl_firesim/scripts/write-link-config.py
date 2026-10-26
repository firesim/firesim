#!/usr/bin/env python3
"""Write the one-kernel SLASH connectivity and requested runtime clock."""
import argparse
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frequency', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.frequency <= 300:
        parser.error('frequency must be in [1, 300] MHz')
    args.output.write_text('[connectivity]\nnk=firesim:1\nsp=firesim_0.m_axi_gmem0:DDR0\n'
                           f'\n[clock]\nkrnl=firesim_0\nfreqhz={args.frequency * 1000000}\n'
                           f'\n[user_region]\npre_synth={Path(__file__).with_name("firesim_presynth.tcl").resolve()}\n')

if __name__ == '__main__':
    main()
