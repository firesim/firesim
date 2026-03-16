#!/usr/bin/env python

import os
import argparse
import csv

from runtime_bar_plot import HPM_COUNTERS_CSV
from post_process_utils import POST_PROCESS_DIR

def find(name, path):
    found = []
    for root, dirs, files in os.walk(path):
        if name in dirs:
            found.append(os.path.join(root, name))
        if name in files:
            found.append(os.path.join(root, name))
    return found

def find_one(name, path):
    found = find(name, path)
    assert len(found) == 1, 'More than one of {} found in {}!'.format(name, path)
    return found[0]

def combine_counter_csvs(a, b, dest):
    """Combines HPM_COUNTER_CSVs by summing the first element of the last row"""
    with open(a, 'r') as f:
        r = [x for x in csv.reader(f, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)]
        a_first = r[0]
        a_last = r[-1]
    with open(b, 'r') as f:
        r = [x for x in csv.reader(f, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)]
        b_first = r[0]
        b_last = r[-1]
    
    assert(a_first == b_first)

    a_cycles = int(a_last[0])
    b_cycles = int(b_last[0])
    cycles = a_cycles + b_cycles
    assert(cycles > a_cycles and cycles > b_cycles)

    if not os.path.exists(os.path.dirname(dest)):
        os.makedirs(os.path.dirname(dest))

    with open(dest, 'w') as f:
        w = csv.writer(f, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        w.writerow(a_first)
        w.writerow([cycles] + (['0'] * (len(a_first)-1)))

XZS = "657.xz_s"
XZS_1 = "657.xz_s-cld"
XZS_2 = "657.xz_s-cpu2006docs"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', help='Directory in which to look for xz_s')
    parser.add_argument('-f', '--force', action='store_true', dest='force',
                        help='Force overwrite files if they exist')
    args = parser.parse_args()

    path = args.directory
    for xzs_1 in find(XZS_1, path):
        # Find associated cpu2006docs
        
        p_dir = os.path.dirname(xzs_1)
        xzs_2 = find_one(XZS_2, p_dir)

        xzs_1_csv = find_one(HPM_COUNTERS_CSV, xzs_1)
        xzs_2_csv = find_one(HPM_COUNTERS_CSV, xzs_2)

        xzs_dir = os.path.join(p_dir, XZS)
        xzs_csv = os.path.join(xzs_dir, POST_PROCESS_DIR, HPM_COUNTERS_CSV)

        if os.path.exists(xzs_csv) and not args.force:
            print('{} already exists!'.format(xzs_csv))
        else:
            print('Combining {} and {} into {}'.format(xzs_1_csv, xzs_2_csv, xzs_csv))
            combine_counter_csvs(xzs_1_csv, xzs_2_csv, xzs_csv)

if __name__ == '__main__':
    main()
