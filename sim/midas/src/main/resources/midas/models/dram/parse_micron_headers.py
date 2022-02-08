#!/usr/bin/env python3

import optparse
import subprocess
import json
import re

# This script generates json files used to drive the memory configuration
# generator by invoking verilator's verilog preprocessor on the micron-provided
# DRAM-model verilog headers. It does this for all combinations of speedgrade X
# DQ width


parser = optparse.OptionParser()
parser.add_option('-f', '--input-file', dest='input_file', help='The verilog header to parse for DDR timings.')
parser.add_option('-o', '--output-file', dest='output_file', help='The output json file name.')
(options, args) = parser.parse_args()

def call_verilator_preprocessor(filename, speedgrade, width):
    #args = ['verilator', '-E', '-D' + width, '-D' + speedgrade, filename]
    args = "verilator  -E -D{0} -D{1} {2}".format(speedgrade, width, filename)
    p = subprocess.Popen(args, shell=True, stdout = subprocess.PIPE)
    return p.stdout.readlines()

def get_units(filename):
    units = {}
    with open(filename, 'rb') as vhf:
        for line in vhf.readlines():
            m = re.search('parameter\s*(\w*).*?\/\/\s*([()\w]+?)\s*(tCK|ps)', line)
            if m:
                units[m.group(1)] = m.group(3)

    return units


values = {}

speedgrades = [ "sg093", "sg107", "sg125", "sg15E", "sg15", "sg187E", "sg187", "sg25E", "sg25",]
widths = ['x4', 'x8', 'x16']

unit_table = get_units(options.input_file)

for sg in speedgrades:
    values[sg] = {};
    for width in widths:
        lines = call_verilator_preprocessor(options.input_file, sg, width)
        values[sg][width] = {};
        for line in lines:
            line_backup = line
            m = re.search('parameter\s*(\w*)\s*=\s*(\w*);', line)
            if m:
                units = unit_table.get(m.group(1), "none")
                try:
                    values[sg][width][m.group(1)] = { "units" : units,
                                                      "value" : int(m.group(2))}
                except ValueError:
                    # Reject string parameters
                    pass

with open(options.output_file, 'wb') as jsonf:
   json.dump(values, jsonf, indent = 4)

