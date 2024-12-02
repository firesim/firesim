#!/usr/bin/env pypy

import argparse
import os

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('num_ranks', type=int, help='Number of DRAM ranks')
    parser.add_argument('input', help='Input command trace CSV')
    parser.add_argument('output_prefix', help='Output filename prefix for DRAMPower')
    parser.add_argument('output_directory', help='Directory for output files')
    parser.add_argument('-s', '--suffix', dest='suffix', default='.trace.csv',
                        help='Suffix for output trace files')
    args = parser.parse_args()

    outdir = args.output_directory
    if not os.path.exists(outdir):
        os.makedirs(outdir)

    output_filenames = [os.path.join(outdir, ''.join([args.output_prefix, str(i), args.suffix]))
                        for i in range(args.num_ranks)]

    cmd_lookup = ['NOP', 'NOP', 'ACT', 'ACT', 'PRE', 'PRE', 'RD', 'RDA', 'WR', 'WRA', 'REF', 'REF']

    output_files = [open(filename, 'w') for filename in output_filenames]
    with open(args.input, 'r') as fin:
        next(fin) # skip first line (header)
        ROLLOVER_CONST = 4294967296 # 2^32
        prev_raw_cycle = 0
        rollover_count = 0
        for line in fin:
            data = line.split(',')
            if len(data) < 6:
                print('Skipping incomplete line: {}'.format(line))
                continue
            raw_cycle = int(data[0]) # Before accounting for rollover
            # Check for rollover
            if (raw_cycle < prev_raw_cycle):
                print('Rolled over from {} to {}, count = {}!'.format(prev_raw_cycle, raw_cycle, rollover_count))
                rollover_count += 1
            prev_raw_cycle = raw_cycle
            cycle = raw_cycle + (rollover_count * ROLLOVER_CONST)
            cmd = int(data[1])
            auto_pre = int(data[5])
            cmdx = cmd_lookup[cmd*2 + auto_pre]
            bank = data[2]
            rank = int(data[3])
            output_files[rank].write(','.join([str(cycle), cmdx, bank]) + '\n')

    for f in output_files:
        f.close()

if __name__ == '__main__':
    main()
