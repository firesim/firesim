#!/usr/bin/env pypy

import argparse
import os
import subprocess
import tempfile
import re
import csv
from threading import Thread

def extant_file(x):
    if not os.path.exists(x):
        raise argparse.ArgumentTypeError('{} does not exist'.format(x))
    else:
        return x

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


def get_power(cycle, dram_power, dram_config, input_file):
    text = invoke_dram_power(dram_power, dram_config, input_file)
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

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('dram_power', type=extant_file, help='DRAMPower binary')
    parser.add_argument('dram_config', type=extant_file, help='DRAMPower XML')
    parser.add_argument('input', type=extant_file, help='Input command trace CSV')
    parser.add_argument('output', help='Output CSV')
    parser.add_argument('cycles', type=int, help='Number of cycles per datapoint')
    args = parser.parse_args()
    return args

def main():
    args = get_args()

    # Puts threads executing DRAM power here
    threads = []
    with open(args.input, 'r') as r:
        w = tempfile.NamedTemporaryFile(dir='.')
        ceiling = args.cycles
        for line in r:
            cycle = int(line.split(',')[0])
            if cycle < ceiling:
                w.write(line)
            else:
                ceiling += args.cycles
                t = ThreadWithReturnValue(target=get_power,
                    args=(cycle, args.dram_power, args.dram_config, w))
                threads.append(t)
                t.start()
                # New tempfile for the next one
                w = tempfile.NamedTemporaryFile(dir='.')
        t = ThreadWithReturnValue(target=get_power,
            args=(cycle, args.dram_power, args.dram_config, w))
        threads.append(t)
        t.start()

    titles = ['Cycle', 'ACT Cmd', 'PRE Cmd', 'RD Cmd', 'WR Cmd', 'ACT Stdby',
              'Active Idle', 'PRE Stdby', 'Precharge Idle', 'Total Idle',
              'Auto-Refresh', 'Total Trace']
    with open(args.output, 'w') as f:
        writer = csv.writer(f, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        writer.writerow(titles)
        for t in threads:
            writer.writerow(t.join())

if __name__ == '__main__':
    main()
