#!/usr/bin/env python
import matplotlib
matplotlib.use('PDF')

import os
import argparse
import json
import re

import matplotlib.pyplot as plt
from matplotlib import rcParams
import numpy as np
from cycler import cycler

from plot_time_series import FileData
from post_process_utils import POST_PROCESS_DIR

FIGURE_DIR = 'figures/'
def mk_figure_dir():
    directory = os.path.expanduser(FIGURE_DIR)
    if not os.path.exists(directory):
        os.makedirs(directory)
    return directory

SPEED_WORKLOADS = [
    "605.mcf_s",
    "620.omnetpp_s",
    "623.xalancbmk_s",
    "602.gcc_s",
    "657.xz_s",
    "631.deepsjeng_s",
    "600.perlbench_s",
    "625.x264_s",
    "641.leela_s",
    "648.exchange2_s"
]
RATE_WORKLOADS = [
    "505.mcf_r",
    "520.omnetpp_r",
    "523.xalancbmk_r",
    "502.gcc_r",
    "557.xz_r",
    "531.deepsjeng_r",
    "500.perlbench_r",
    "525.x264_r",
    "541.leela_r",
    "548.exchange2_r"
]
def get_workloads(suite):
    if suite == "rate":
        return RATE_WORKLOADS
    elif suite == "speed":
        return SPEED_WORKLOADS
    else:
        raise Exception('Invalid suite {}, must be "rate" or "speed"!'.format(suite))
HPM_COUNTERS_CSV = "hpm_counters0.csv"

def prune_workload_name(w):
    m = re.match('\d\d\d\.(\w+)_(?:s|r)', w)
    if m:
        return m.group(1)
    else:
        raise Exception('Cannot prune workload {}!'.format(w))

class Benchmark(object):
    """A container for information about a benchmark to be plotted."""

    def validate(self):
        """Check that some assumptions hold"""
        if not os.path.isdir(self.directory):
            msg = 'Benchmark {} directory {} does not exist!'.format(self.name, self.directory)
            raise Exception(msg)
        # All workloads must be defined
        for w in self.workloads:
            wdir = os.path.join(self.directory, w)
            if not os.path.isdir(wdir):
                msg = 'Benchmark {} workload directory {} does not exist!'.format(self.name, wdir)
                raise Exception(msg)

    def __init__(self, name, suite, directory):
        self.name = name
        self.directory = directory
        self.workloads = get_workloads(suite)
        self.suite = suite
        self.hpm_filedata = None
        self.validate()

    def hpm_counters_file(self, workload):
        f = os.path.join(self.directory, workload, POST_PROCESS_DIR, HPM_COUNTERS_CSV)
        assert(os.path.exists(f))
        return f

    def get_hpm_counters_data(self):
        if self.hpm_filedata:
            return self.hpm_filedata
        else:
            self.hpm_filedata = {w: FileData(self.hpm_counters_file(w), w) for w in self.workloads}
            return self.hpm_filedata

    def get_workload_runtimes(self):
        filedatas = self.get_hpm_counters_data()
        runtimes_map = {w: datafile.get_cycles()[-1] for (w, datafile) in filedatas.iteritems()}
        print('Workload Runtimes for {}'.format(self.name))
        data = []
        for w in self.workloads:
            rt = runtimes_map[w]
            print('  {}: {}'.format(w, rt))
            data.append(rt)
        return data

def geo_mean_overflow(iterable):
    a = np.log(iterable)
    return np.exp(a.sum()/len(a))

def get_color_list(num_points):
    return plt.cm.seismic(np.linspace(0.5, 0, num_points+1)).tolist()[1:]

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
    fig, ax = plt.subplots(figsize=(8, 4))
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

    ax.set_ylabel('Speedup')
    ax.set_xlabel('int' + suite)

    ticks = ind + width * (N_benchmarks / 2)
    ax.set_xticks(ticks)
    xtitles = [prune_workload_name(w) for w in workloads] + ["Geomean"]
    ax.set_xticklabels(xtitles, rotation=45, ha='right')

    plt.legend(bbox_to_anchor=(0.68, 0.58))
    plt.savefig(mk_figure_dir() + '/cache_size_bar_plot.pdf')

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', dest='config_file',
                        help='A JSON file contains information about benchmarks to plot')
    args = parser.parse_args()
    benchmarks = []

    if args.config_file:
        with open(args.config_file, 'r') as f:
            data = json.load(f)
            directory = os.path.expanduser(data["directory"])
            suite = data["suite"]
            for b in data["benchmarks"]:
                bdir = os.path.join(directory, b["directory"])
                benchmarks.append(Benchmark(b["name"], suite, bdir))
    else:
        parser.error('Must provide config_file')

    return benchmarks

def main():
    benchmarks = get_args()
    plot_workload_speedup(benchmarks)
     

if __name__ == '__main__':
    main()
