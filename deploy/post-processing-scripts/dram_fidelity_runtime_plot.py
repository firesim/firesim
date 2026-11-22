#!/usr/bin/env python
import matplotlib
matplotlib.use('PDF')

import os
import argparse
import json

import matplotlib.pyplot as plt
from matplotlib import rcParams
import numpy as np
from cycler import cycler

from plot_time_series import FileData
from runtime_bar_plot import Benchmark, get_workloads, geo_mean_overflow, get_color_list
from runtime_bar_plot import mk_figure_dir, prune_workload_name

class DRAMFeatureExperiment(object):
    """A container for plotting the relative slowdown of adding DRAM features
       Each "feature" is represented as a Benchmark
    """

    def __init__(self, plotfile, features, suite):
        self.plotfile = plotfile
        self.features = features
        self.suite = suite

    def plot(self):
        print(' ***** Plotting slowdown due to model fidelity *****')
        workloads = self.features[0].workloads
        N_bars = len(workloads) + 1 # + geomean
        N_features = len(self.features)
        ind = np.arange(N_bars)
        width = 1.0 / (N_features + 2)

        rcParams.update({'figure.autolayout': True}) # Helps with not cutting off the x labels
        fig, ax = plt.subplots(figsize=(8, 4))
        colors = get_color_list(N_features)
        ax.set_prop_cycle(cycler('color',colors))

        # Plot baseline as 1
        baseline = self.features[0]
        baseline_runtimes = baseline.get_workload_runtimes()
        print('  Geomean: {}'.format(1.0))
        ax.bar(ind, [1.0] * N_bars, width = width, label = baseline.name, edgecolor = 'black')
        for i, feature in enumerate(self.features[1:]):
            runtimes = feature.get_workload_runtimes()
            norm_times = [float(u) / v for (u, v) in zip(runtimes, baseline_runtimes)]
            geomean = geo_mean_overflow(norm_times)
            print('  Geomean: {}'.format(geomean))
            data = norm_times + [geomean]
            rect = ax.bar(ind + (i+1)*width, data, width = width, label = feature.name,
                          edgecolor='black')

        ax.set_ylabel('Normalized Execution Time')
        ax.set_xlabel('int' + self.suite)

        ticks = ind + width * (N_features / 2)
        ax.set_xticks(ticks)
        xtitles = [prune_workload_name(w) for w in workloads] + ["GeoMean"]
        ax.set_xticklabels(xtitles, rotation=45, ha='right')

        plt.legend(bbox_to_anchor=(0.68, 0.58))
        plt.savefig('{}/{}'.format(mk_figure_dir(), self.plotfile))

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', dest='config_file',
                        help='A JSON file contains information about feature experiments to plot')
    args = parser.parse_args()
    experiments = []
    if args.config_file:
        with open(args.config_file, 'r') as f:
            data = json.load(f)
            directory = os.path.expanduser(data["directory"])
            for e in data["experiments"]:
                plotfile = e["plotfile"]
                suite = e["suite"]
                features = []
                for f in e["features"]:
                    fname = f["name"]
                    fdir = os.path.join(directory, f["directory"])
                    features.append(Benchmark(fname, suite, fdir))
                experiments.append(DRAMFeatureExperiment(plotfile, features, suite))

    else:
        parser.error('Must provide config_file')

    return experiments

def main():
    experiments = get_args()
    for e in experiments:
        e.plot()


if __name__ == '__main__':
    main()
