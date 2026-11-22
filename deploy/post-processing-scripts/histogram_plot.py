#!/usr/bin/env python
import matplotlib
matplotlib.use('PDF')

import os
import argparse
import csv
import json
import math
from collections import OrderedDict

import matplotlib.pyplot as plt
from matplotlib import rcParams
import numpy as np

from runtime_bar_plot import get_color_list, mk_figure_dir


class LatencyHistogram(object):
    """A container for plotting latency histograms"""

    def __init__(self, name, data):
        self.name = name

        # Pick an x-max
        xmax = 100
        self.data = { x: d for (x, d) in enumerate(data) if x < xmax}

def plot_histograms(histograms, plotname):

    num_plots = len(histograms)

    rcParams.update({'figure.autolayout': True}) # Helps with not cutting off the x labels
    fig, subplots = plt.subplots(num_plots, figsize=(11, 2 * num_plots))

    for (histogram, ax) in zip(histograms, subplots):
        data = histogram.data
        ind = data.keys()
        ax.bar(ind, data.values())
        ax.set_title(histogram.name)

        # Calcuate average latency and print it out
        total_latency = sum([i*bin for (i, bin) in enumerate(data.values())])
        num_points = sum(data.values())
        average_latency = total_latency / float(num_points)
        sum_of_squares = sum([ lbin * ((float(i) - average_latency)**2) for (i, lbin) in enumerate(data.values())])
        std_deviation = math.sqrt(sum_of_squares/num_points)
        ninety_fifth_idx = num_points/2
        print ninety_fifth_idx
        for (i, lbin) in enumerate(data.values()):
            if (ninety_fifth_idx - lbin < 0):
                ninety_fitth_latency = i
            else:
                ninety_fifth_idx = ninety_fifth_idx - lbin


        print "  {} Average: {:.2f} STDDev: {:.2f} 95%: {} ".format(histogram.name, average_latency, std_deviation, ninety_fitth_latency)

    if not os.path.exists(os.path.dirname(plotname)):
        os.makedirs(os.path.dirname(plotname))

    plt.savefig(plotname)

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('config_file', help='A JSON containing the configuration')
    parser.add_argument('input_file', help='A CSV file that contains latencies')
    parser.add_argument('output_file', help='A CSV file that contains latencies')
    args = parser.parse_args()
    keymap = OrderedDict()

    with open(args.config_file, 'r') as f:
        data = json.load(f)
        for dataset in data["datasets"]:
            name = dataset["name"]
            key = dataset["key"]
            keymap[key] = (name, [])

    with open(args.input_file, 'r') as f:
        reader = csv.DictReader(f, delimiter=',')
        for row in reader:
            for (key, data) in keymap.iteritems():
                data[1].append(int(row[key]))
    histograms = [LatencyHistogram(x[0], x[1]) for x in keymap.values()]

    return (histograms, args.output_file)

def main():
    histograms, output_file = get_args()
    plot_histograms(histograms, output_file)


if __name__ == '__main__':
    main()
