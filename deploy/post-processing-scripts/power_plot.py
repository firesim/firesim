#!/usr/bin/env python
import matplotlib
matplotlib.use('PDF')

import os
import argparse
import json
import re
import sys
import csv

import matplotlib.pyplot as plt
from matplotlib import rcParams
import numpy as np
from cycler import cycler

from plot_time_series import FileData
from runtime_bar_plot import mk_figure_dir

# Global Varibles WHATS GOOD!?!?
NUM_RANKS = 4

def plot_workload_speedup(benchmarks):

    print(' ***** Plotting normalized runtime due to cache configuration *****')

    baseline = benchmarks[0]
    workloads = baseline.workloads
    suite = baseline.suite

    # Check that all benchmarks have matching workloads
    for b in benchmarks:
        assert workloads == b.workloads

    N_bars = len(workloads) + 1 # + geomean
    N_benchmarks = len(benchmarks)
    ind = np.arange(N_bars)
    width = 1.0 / (N_benchmarks + 2) # width of 2 bars between each cluster

    rcParams.update({'figure.autolayout': True}) # Helps with not cutting off the x labels
    fig, ax = plt.subplots()
    ax.set_prop_cycle(cycler('color',get_color_list(N_benchmarks)))

    # Normalize to the very first benchark
    baseline_runtimes = baseline.get_workload_runtimes()
    print('  Geomean: {}'.format(1.0))
    ax.bar(ind, [1.0] * N_bars, width=width, label=baseline.name, edgecolor='black')

    for i, benchmark in enumerate(benchmarks[1:]):
        runtimes = benchmark.get_workload_runtimes()
        # Speedup is the baseline runtime divided by our runtime
        norm_times = [u / v for (u, v) in zip(baseline_runtimes, runtimes)]
        geomean = geo_mean_overflow(norm_times)
        print('  Geomean: {}'.format(geomean))
        data = norm_times + [geomean]
        rect = ax.bar(ind + (i+1)*width, data, width=width, label=benchmark.name,
                      edgecolor='black')

    ax.set_ylabel('Normalized Execution Time')
    ax.set_xlabel('int' + suite)

    ticks = ind + width * (N_benchmarks / 2)
    ax.set_xticks(ticks)
    xtitles = [prune_workload_name(w) for w in workloads] + ["Geomean"]
    ax.set_xticklabels(xtitles, rotation=45, ha='right')

    plt.legend()
    plt.savefig(mk_figure_dir() + '/cache_size_bar_plot.pdf')


def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('data_directory', help='Directory containing power CSVs, one per rank')
    args = parser.parse_args()

    ddir = args.data_directory
    input_files = [os.path.join(ddir, f) for f in os.listdir(ddir)]
    assert len(input_files) == 4, 'There should be 4 files in {}, got {}!'.format(ddir, len(input_files))

    return input_files

def read_lines(dictreader):
    lines = []
    for line in dictreader:
        lines.append(line)
    return lines

TARGET_CLOCK=1000000000 # 1 GHz
CYCLE_THRESH=10000

def get_power(readers):
    data = [read_lines(r) for r in readers]
    result = []
    prev_cycles = 0
    for i in range(len(data[0])):
        rows = [d[i] for d in data]
        energy = [r['Total Trace'] for r in rows]
        each_cycles = [r['Cycle'] for r in rows]
        # TODO assert cycles are close
        cycles = int(each_cycles[0])
        for c in each_cycles:
            diff = abs(cycles - int(c))
            assert diff < CYCLE_THRESH, 'Cycle difference of {} larger than {}'.format(diff, CYCLE_THRESH)
        cycles_diff = cycles - prev_cycles
        total_energy_pJ = sum([float(e) for e in energy])
        total_energy_J = total_energy_pJ * 1e-12
        seconds = float(cycles_diff) / TARGET_CLOCK
        watts = total_energy_J / seconds
        print(energy[0])
        result.append([cycles, watts])
        prev_cycles = cycles
    return result

PLOTNAME='power_plot.pdf'

def plot_power(power):
    xs = [d[0] for d in power]
    ys = [d[1] for d in power]

    plt.plot(xs, ys)
    plt.ylabel('Power (Watts)')
    plt.xlabel('Cycles')


    plt.savefig(os.path.join(mk_figure_dir(), PLOTNAME))
    

def main():
    input_files = get_args()
   
    files = [open(filename, 'r') for filename in input_files]
    readers = [csv.DictReader(f) for f in files]

    power = get_power(readers)

    for f in files:
        f.close()

    plot_power(power)

     

if __name__ == '__main__':
    main()
