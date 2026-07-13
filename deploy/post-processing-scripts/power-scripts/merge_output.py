#!/usr/bin/env pypy

import argparse
import os
import subprocess
import tempfile
import re
import csv
from threading import Thread
from collections import defaultdict

def extant_file(x):
    if not os.path.exists(x):
        raise argparse.ArgumentTypeError('{} does not exist'.format(x))
    else:
        return x

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('output_prefix', help='output file prefixes')
    parser.add_argument('input_files_dir', type=extant_file, help='directory containing input files')
    args = parser.parse_args()

    return args

def order_inputs(input_files):
    ranks = defaultdict(dict)
    for fname in input_files:
        basename = os.path.basename(fname)
        data = basename.split('.')
        assert len(data) == 4, 'len(data) == {}, {}'.format(len(data), data)
        idx = int(data[0])
        rank = int(data[1][-1])
        ranks[rank][idx] = fname

    res = {}
    for rank, values in ranks.iteritems():
        idx = sorted(values.keys())
        res[rank] = [values[i] for i in idx]
    return res


# Hardcoded 4 ranks
def main():
    args = get_args()

    input_files = [os.path.join(args.input_files_dir, f) for f in os.listdir(args.input_files_dir)]

    data = order_inputs(input_files)

    TITLES = ['Cycles', 'ACT Cmd', 'PRE Cmd', 'RD Cmd', 'WR Cmd', 'ACT Stdby',
              'Active Idle', 'PRE Stdby', 'Precharge Idle', 'Total Idle',
              'Auto-Refresh', 'Total Trace']

    for rank, files in data.iteritems():
        output_filename = '{}.rank{}.csv'.format(args.output_prefix, rank)
        with open(output_filename, 'w') as out:
            out.write(','.join(TITLES) + '\n')
            for input_file in files:
                print('Reading {}'.format(input_file))
                with open(input_file, 'r') as fin:
                    for line in fin.readlines():
                        out.write(line)


if __name__ == '__main__':
    main()
