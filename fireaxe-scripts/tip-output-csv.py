#!/usr/bin/env python3

import pandas as pd
import os
import argparse

parser = argparse.ArgumentParser(description="Arguments for processing TIP outputs")
parser.add_argument("--tip-results-dir", type=str, required=True, help="Directory containing tip outputs")
args = parser.parse_args()

CWD=os.getcwd()
INTERMEDIATE_DIR=os.path.join(CWD, f"tip-intermediate/{args.tip_results_dir}")
files = os.listdir(INTERMEDIATE_DIR)

HEADER='name,time,commit,ld_stall,st_stall,alu_stall,frontend,misspec,misc'

def get_data(file):
    with open(file, 'r') as f:
        lines = f.readlines()
        data = lines[1]
        words = data.split()
        return words

def output_csv():
    print(HEADER)
    for f in files:
        data = get_data(os.path.join(INTERMEDIATE_DIR, f))
        csv_line = ','.join(data)
        print(f + ',' + csv_line)

if __name__ == '__main__':
    output_csv()
