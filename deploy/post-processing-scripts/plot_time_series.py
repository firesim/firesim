#! /usr/bin/env python

# Author: Christopher Celio
#   
import matplotlib
from functools import partial
matplotlib.use('PDF') # must be called immediately, and before import pylab
                      # sets the back-end for matplotlib

import matplotlib.pyplot as plt
import numpy as np
import pylab
import csv
import optparse
import os
from mpl_toolkits.axes_grid1 import host_subplot # for multi axes
import mpl_toolkits.axisartist as AA # for multi axes

# Local libraries
from post_process_utils import POST_PROCESS_DIR

cmap = matplotlib.pyplot.get_cmap("viridis_r")
#print dir(cmap)

max_colors = 12
fake_max=2
color_intensities = [float(x)/(max_colors-1) for x in range(max_colors)]
vircol = [matplotlib.cm.viridis(c) for c in color_intensities]

TITLE = "RISC-V Rocket running 403.gcc (with reference inputs)"
BNAME = "403.gcc.ref"

def getColor(idx, maxidx):
    val = ((idx)*max_colors/maxidx) % max_colors
    print "i:",idx,"m:",maxidx," -> ",val
    return vircol[val]


data_dir = "csv_data"
# provide both the file name (in graphs) and the data title
data_files_list = [
    (BNAME + "-1-8.csv", " 1 cycle DRAM Latency"),
    (BNAME + "-30-8.csv", "30 cycle DRAM Latency")
    ]

# further options
PLOT_IPC=True# plot CPI (as captured) or the inverse IPC?
PLOT_X_CYCLES=True # plot Cycles on X-axis, or Instructions?
RUNNING_AVG_SZ=10

# from http://stackoverflow.com/questions/13728392/moving-average-or-running-mean
def runningMeanFast(x, N):
    return np.convolve(x, np.ones((N,))/N,mode='valid')

# plot ydata_num/ydata_denom
def plotCounterInstantAndCumulative(fig, data, x_func, y_func, label, i=0, color=c):
    y_points = y_func(data)
    x_points = x_func(data)

#    y_points = np.array([y_point for index, y_point in enumerate(y_points) if index % 10 == 9])
#    x_points = np.array([x_point for index, x_point in enumerate(x_points) if index % 10 == 9])

    print y_points
    print x_points

    fig.plot(x_points, y_points, color=getColor(i, fake_max))
    fig.yaxis_labelpad = -0

    font = {#'family' : 'normal',
            'weight' : 'bold',
            'size'   : 11}
    matplotlib.rc('font', **font)
    fig.set_ylabel(label)
    fig.yaxis.labelpad = -0
    fig.set_ylim([y_points.min(), y_points.max()])


def getDeltas(lst):
    return np.diff(lst)

def getRatio(a, b):
    return [(float(x)/float(y)) for (x,y) in zip(a, b)]

class FileData(object):
    """A container around data parsed from a CSV."""

    def __init__(self, filename, name):
        self.name = name
        with open (filename, 'rb') as csvfile:
            reader = csv.reader(csvfile, delimiter=',',quotechar='|')
            labels = next(reader)

            rows = [x for x in reader]
            self.data_length = len(rows)
            self.data = {}
            idx = 0
            for idx, field in enumerate(labels):
                self.data[field] = np.array(map(float, [r[idx] for r in rows]))

            #print self.data

    def get_cycles(self):
        return self.data["Cycle Count"]

    # Returns an array of data associated with the desired field aggregated from time 0
    def get_cumulative(self, name):
        return self.data[name]

    # Returns the change in a value over each sample interval
    def get_deltas(self, name):
        cumulative = self.get_cumulative(name)
        deltas =  np.diff(cumulative)
        return deltas

def get_cycles(data, discard_num_head = 0):
    return data.get_cycles()[discard_num_head:]

def scale_deltas(data, field, scale):
    return data.get_deltas(field) * scale

def ratio_deltas(data, numerator_fields, denominator_fields, scale = 1, running_mean = RUNNING_AVG_SZ):
    numerators = reduce(lambda x, y: x + y, [data.get_deltas(field) for field in numerator_fields])
    denominators = reduce(lambda x, y: x + y, [data.get_deltas(field) for field in denominator_fields])
    return runningMeanFast(scale * (numerators / denominators), running_mean)

# With the above sometimes you can only calculate a hit (miss)  rate; this gives the miss (hit) rate
def one_sub_ratio(data, numerator_fields, denominator_fields, scale = 1, running_mean = RUNNING_AVG_SZ):
    ratios = ratio_deltas(data, numerator_fields, denominator_fields, running_mean = running_mean)
    ones = np.ones(ratios.size)
    return scale * (ones - ratios)

def get_multi_field(prefix, suffix, num):
    return [prefix + str(i) + suffix for i in xrange(num)]

all_casr = get_multi_field("rankPower_", "_numCASR", 4)
all_casw = get_multi_field("rankPower_", "_numCASW", 4)
all_act = get_multi_field("rankPower_", "_numACT", 4)

def get_rank_field(rank, field):
    return "rankPower_" + str(rank) + field

rank_act = partial(get_rank_field, field = "_numACT")
rank_casw = partial(get_rank_field, field = "_numCASW")
rank_casr = partial(get_rank_field, field = "_numCASR")

plot_activations = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = all_act,
                     denominator_fields = ["Cycle Count"],
                     scale = 1000),
    label = "Activations per KCycle")

plot_llc_miss_rate = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(one_sub_ratio,
                     numerator_fields = ["llc_misses"],
                     denominator_fields = ["totalReads", "totalWrites"],
                     scale = 100),
    label = "LLC Hit %")

plot_reads = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = all_casr,
                     denominator_fields = ["Cycle Count"],
                     scale = 1000),
    label = "Reads per KCycle")

plot_writes = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = all_casw,
                     denominator_fields = ["Cycle Count"],
                     scale = 1000),
    label = "Writes per KCycle")

plot_hit_rate = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(one_sub_ratio,
                     numerator_fields = all_act,
                     denominator_fields = all_casr + all_casw,
                     scale = 100),
    label = "Row Buffer Hit %")

def plot_rank_hit_rate(rank):
    return partial(plotCounterInstantAndCumulative,
        x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
        y_func = partial(one_sub_ratio,
                         numerator_fields = [rank_act(rank)],
                         denominator_fields = [rank_casr(rank), rank_casw(rank)]),
        label = "Rank {} Row Buffer Hit Rate ".format(rank))

plot_read_write_ratio = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = all_casr,
                     denominator_fields = all_casr + all_casw,
                     ),
    label = "Read Fraction")

plot_references = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = all_casr + all_casw,
                     denominator_fields = ["Cycle Count"],
                     scale = 1000),
    label = "Memory References")

plot_CPI = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = ["Cycle Count"],
                     denominator_fields = ["Instructions Retired"]),
    label = "CPI")

plot_DMPKI = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = ["D$ Misses"],
                     denominator_fields = ["Instructions Retired"],
                     scale = 1000),
    label = "D$ MPKI")

plot_IMPKI = partial(
    plotCounterInstantAndCumulative,
    x_func = partial(get_cycles, discard_num_head = RUNNING_AVG_SZ),
    y_func = partial(ratio_deltas,
                     numerator_fields = ["I$ Misses"],
                     denominator_fields = ["Instructions Retired"],
                     scale = 1000),
    label = "I$ MPKI")
def main():

    parser = optparse.OptionParser()
    parser.add_option('-f', '--file', dest='filename', help='input command file')
    parser.add_option('-b', '--benchmark',
            dest='bmark_dir',
            help='Generates plots all workloads in a benchmark suite.')
    (options, args) = parser.parse_args()

    midas_csv_files = []
    hpm_csv_files = []
    if options.bmark_dir:
        workload_dirs = os.listdir(options.bmark_dir)
        for workload in workload_dirs:
            csv_file_path = options.bmark_dir + "/" + workload
            midas_csv_files.append(FileData(csv_file_path + "/" + POST_PROCESS_DIR + "/memory_stats_processed.csv", workload))
            hpm_csv_files.append(FileData(csv_file_path + "/" + POST_PROCESS_DIR + "/hpm_counters0.csv", workload))
    elif options.filename:
        dname = options.filename.split('/')[-1]
        bname = dname.split('.csv')[0]
        data_files = ((dname, "") ,)
        print options.filename
        midas_csv_files.append(FileData(options.filename, bname))
    else:
        parser.error('Please give an input filename with -f')


    midas_plots = [plot_llc_miss_rate, plot_hit_rate]
    hpm_plots = [plot_CPI, plot_DMPKI]
    num_plot_types = len(midas_plots + hpm_plots)

    num_subplots = num_plot_types * len(midas_csv_files)
    fig, subplots_sq = plt.subplots(2, 2, figsize=(11,0.6 * num_subplots))

    subplots = [subplots_sq[0,0], subplots_sq[1,0], subplots_sq[0,1], subplots_sq[1,1]]

    #for idx in range(1, num_subplots + 1):
    ## turn off labels, keep tick marks
    #    plt.subplot(num_subplots, 1, idx)
    #    subplots[idx - 2].axes.xaxis.set_ticklabels([])

    #font = {#'family' : 'normal',
    #        'weight' : 'bold',
    #        'size'   : 12}
    #matplotlib.rc('font', **font)
    colors =(getColor(0,2), getColor(1,2))

    for index, (midas_data, hpm_data) in enumerate(zip(midas_csv_files, hpm_csv_files)):

        #subplots[index * num_plot_types].set_title("Benchmark: " + midas_data.name, fontsize=12) # + " (reference)")
        # For some reason i need to do the head individually
        hpm_plots[0](subplots[index*num_plot_types], hpm_data)
        for hsp_idx, plot_func in enumerate(hpm_plots[1:]):
            plot_func(subplots[index*num_plot_types + hsp_idx + 1], hpm_data)

        for hsp_idx, plot_func in enumerate(midas_plots):
            plot_func(subplots[index*num_plot_types + len(hpm_plots) + hsp_idx], midas_data)

        max_cycle = hpm_data.get_cycles()[-1]

        print "plotting..."
        for sp in subplots:
            sp.set_xlim([0, max_cycle])
            [tick.label.set_fontsize(10) for tick in sp.yaxis.get_major_ticks()]

        subplots[0].set_xticklabels([])
        subplots[2].set_xticklabels([])

        #[tick.label.set_fontsize(10) for tick in p2.yaxis.get_major_ticks()]
        # xticks
        tail_subplot = subplots[3]
        locs = tail_subplot.get_xticks()
        #print "locs:"
        #print locs
        #if max([int(x) for x in locs]) > 5e12:
        #tail_subplot.set_xticklabels([map(lambda x: "%5.1f" % (float(x)/1e12), locs)])
        x_label_scale = "Trillions"
        #elif max([int(x) for x in locs]) > 5e9:
        #    tail_subplot.set_xticklabels(locs, map(lambda x: "%5.1f" % (float(x)/1e9), locs))
        #    x_label_scale = "Billions"
        #elif max([int(x) for x in locs]) > 5e6:
        #    tail_subplot.set_xticklabels(locs, map(lambda x: "%5.1f" % (float(x)/1e6), locs))
        #    x_label_scale ="Millions"
        #else:
        #    tail_subplot.set_xticklabels(locs, map(lambda x: "%5.1f" % (float(x)/1e3), locs))
        #    x_label_scale ="Thousands"
        subplots[1].tick_params(axis='y', which='major', labelsize=11)
        subplots[3].tick_params(axis='y', which='major', labelsize=11)



        if PLOT_X_CYCLES:
            subplots[1].set_xlabel('Cycles (' + x_label_scale + ')')
            subplots[3].set_xlabel('Cycles (' + x_label_scale + ')')
        else:
            tail_subplot.set_xlabel('Instructions Retired (' + x_label_scale +')')


    gname = "./graphs/test.pdf"
    plt.tight_layout(pad=0.0, w_pad=0.5, h_pad=0.5)
    plt.savefig(gname)
    print ("Saving plot at : " + gname)


if __name__ == '__main__':
    main()
    #print 'finished with main from CLI'
