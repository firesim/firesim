#!/usr/bin/env pypy

import argparse
import os
import subprocess
import tempfile
import re
import csv
from threading import Thread

# Thanks https://stackoverflow.com/questions/6893968
class ThreadWithReturnValue(Thread):
    def __init__(self, group=None, target=None, name=None,
                 args=(), kwargs={}, Verbose=None):
        Thread.__init__(self, group, target, name, args, kwargs, Verbose)
        self._return = None
    def run(self):
        if self._Thread__target is not None:
            self._return = self._Thread__target(*self._Thread__args,
                                                **self._Thread__kwargs)
    def join(self):
        Thread.join(self)
        return self._return

def invoke_dram_power(dram_power, dram_config, input_file):
    input_file.seek(0) # Return to start
    abs_binary = os.path.abspath(dram_power)
    abs_config = os.path.abspath(dram_config)
    abs_input = os.path.abspath(input_file.name)
    cmd = [abs_binary, '-m', abs_config, '-c', abs_input]
    process = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    out, err = process.communicate()
    input_file.close()
    return out

def extract_energy(text, name, suffix='Energy'):
    for line in text.split('\n'):
        m = re.match('\s*{}\s+{}\s*:\s+(\d+\.\d+)\s+pJ\s*'.format(name, suffix), line)
        if m:
            return float(m.group(1))
    raise Exception('{} not found!'.format(name))


def get_power(dram_power, dram_config, input_file):
    text = invoke_dram_power(dram_power, dram_config, input_file)
    cycle = None
    for line in text.split('\n'):
        m = re.match('\s*Total Trace Length \(clock cycles\):\s+(\d+)\s*', line)
        if m:
            cycle = m.group(1)
            break
    assert cycle != None
    res = [
        cycle,
        extract_energy(text, 'ACT Cmd'),
        extract_energy(text, 'PRE Cmd'),
        extract_energy(text, 'RD Cmd'),
        extract_energy(text, 'WR Cmd'),
        extract_energy(text, 'ACT Stdby'),
        extract_energy(text, 'Active Idle'),
        extract_energy(text, 'PRE Stdby'),
        extract_energy(text, 'Precharge Idle'),
        extract_energy(text, 'Total Idle', suffix='Energy \(Active \+ Precharged\)'),
        extract_energy(text, 'Auto-Refresh'),
        extract_energy(text, 'Total Trace')
    ]
    return res

def extant_file(x):
    if not os.path.exists(x):
        raise argparse.ArgumentTypeError('{} does not exist'.format(x))
    else:
        return x

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('dram_power', type=extant_file, help='DRAMPower binary')
    parser.add_argument('dram_config', type=extant_file, help='DRAMPower XML')
    parser.add_argument('input_filename', type=extant_file, help='Input command trace CSV')
    parser.add_argument('output_prefix', help='Output filename prefix for DRAMPower processed numbers')
    parser.add_argument('output_directory', help='Output directory')
    args = parser.parse_args()

    return args


CMD_LOOKUP = ['NOP', 'NOP', 'ACT', 'ACT', 'PRE', 'PRE', 'RD', 'RDA', 'WR', 'WRA', 'REF', 'REF']
NUM_RANKS = 4
ROLLOVER_CONST = 4294967296 # 2^32
SUFFIX = '.power.csv'

def extract_info(line):
    data = line.split(',')
    if len(data) < 6:
        return None
    elif data[0][0] == 't':
        return None
    raw_cycle = int(data[0])
    cmd = int(data[1])
    auto_pre = int(data[5])
    cmdx = CMD_LOOKUP[cmd*2 + auto_pre]
    bank = data[2]
    rank = int(data[3])

    return (raw_cycle, cmdx, bank, rank)

# Hardcoded 4 ranks
def main():
    args = get_args()

    out_dir = args.output_directory
    output_filenames = [os.path.join(out_dir, ''.join([args.output_prefix, '.rank', str(i), SUFFIX]))
                        for i in range(NUM_RANKS)]

    temp_files = [tempfile.NamedTemporaryFile(dir='tmps') for i in range(NUM_RANKS)]
    with open(args.input_filename, 'r') as fin:
        prev_raw_cycle = 0
        rollover_acc = 0
        for line in fin:
            res = extract_info(line)
            if res == None:
                print('Skipping line {}'.format(res))
                continue
            (raw_cycle, cmd, bank, rank) = res
            # Check for rollover
            if (raw_cycle < prev_raw_cycle):
                print('Rolled over from {} to {}!'.format(prev_raw_cycle, raw_cycle))
                rollover_acc += ROLLOVER_CONST
            prev_raw_cycle = raw_cycle
            cycle = raw_cycle + rollover_acc
            temp_files[rank].write(','.join([str(cycle), cmd, bank]) + '\n')

    # Now invoke DRAM Power for each rank
    for rank in range(NUM_RANKS):
        power = get_power(args.dram_power, args.dram_config, temp_files[rank])
        temp_files[rank].close()
        with open(output_filenames[rank], 'w') as f:
            f.write(','.join([str(p) for p in power] + ['\n']))

if __name__ == '__main__':
    main()
