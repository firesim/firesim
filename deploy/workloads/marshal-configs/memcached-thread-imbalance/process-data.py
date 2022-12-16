# requires see package requirements below
#
# usage:
# python process.py PATH_TO_results-workload_DIRECTORY_FOR_WORKLOAD
# this directory is expected to contain ./mutilatemaster-4/uartlog
# and ./mutilatemaster-5/uartlog

# you must pip install: pandas, matplotlib
# you must yum install tkinter

import sys

import matplotlib
# don't use xwindow
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import pandas as pd
import numpy as np
import re
import sys


starterpath = sys.argv[1]
percentilecompares = ["95th", "99th"]
# scaling based on RTC conversion (1.0 if your RTC is set to 3.2 GHz)
multiplier = 1.0


def file_to_rows(fname, indentcells=0, postcells=0, compareagainst="95th"):
    # adjust for the fact that our RTC is off

    a = open(fname)
    b = a.readlines()
    a.close()

    rows = []

    indexes = {"50th": 6, "95th": 8, "99th": 9}

    # row fmt:
    # QPS, 50th percentile, 95th percentile

    rowinprog = [''] * indentcells
    postcellsrow = [''] * postcells
    for line in b:
        if "read    " in line:
            line = line.split()
            rowinprog.append(str(float(line[indexes["50th"]]) / multiplier))
            rowinprog.append(str(float(line[indexes[compareagainst]]) / multiplier))
        if "Total QPS = " in line:
            line = line.split()
            rows.append([str(float(line[3]) * multiplier)] + rowinprog + postcellsrow)
            rowinprog = [''] * indentcells

    return rows


def write_csv_rows(outfname, rows):
    a = open(outfname + ".csv", "w")

    for row in rows:
        a.write(",".join(row) + "\n")

    a.close()

def do_plotting(filename, comparison):
    df = pd.read_csv(filename + '.csv')
    colors_to_use =["0.0", "0.35", "0.7", "0.0", "0.6", "0.7"]

    print(df)
    ax = df.plot(kind='scatter', x='QPS', y='4-thread-50th', marker='s', label='4 threads, 50th percentile', c=colors_to_use[0])
    df.plot(kind='scatter', x='QPS', y='4-thread-pinned-50th', marker='o', label='4 threads pinned, 50th percentile', c=colors_to_use[1], ax=ax)
    df.plot(kind='scatter', x='QPS', y='5-thread-50th', marker='x', label='5 threads, 50th percentile', c=colors_to_use[2], ax=ax)
    df.plot(kind='scatter', x='QPS', y='4-thread-' + comparison, marker='D', label='4 threads, ' + comparison + ' percentile', c=colors_to_use[3], ax=ax)
    df.plot(kind='scatter', x='QPS', y='4-thread-pinned-' + comparison, marker='+', label='4 threads pinned, ' + comparison + ' percentile', c=colors_to_use[4], ax=ax)
    df.plot(kind='scatter', x='QPS', y='5-thread-' + comparison, marker='^', label='5 threads, ' + comparison + ' percentile', c=colors_to_use[5], ax=ax)
    ax.set_xlabel("Queries Per Second", size='10')
    ax.set_ylabel(r'Request Latency ($\mu$s)', size='10')
    ax.grid(linestyle='-', linewidth=0.3)
    fig = plt.gcf()
    fig.set_size_inches(6, 3.75)
    fig.savefig(starterpath + comparison + '-memcached-request-latency.pdf', format='pdf')


def full_script(percentilecompare):
    fname1 = starterpath + "/mutilatemaster-4/uartlog"
    fname2 = starterpath + "/mutilatemaster-5/uartlog"
    fname3 = starterpath + "/mutilatemaster-4-pinned/uartlog"
    first95 = file_to_rows(fname1, 0, 4, percentilecompare)
    second95 = file_to_rows(fname2, 2, 2, percentilecompare)
    third95 = file_to_rows(fname3, 4, 0, percentilecompare)

    header = [["QPS", "4-thread-50th", "4-thread-" + percentilecompare, "5-thread-50th", "5-thread-" + percentilecompare, "4-thread-pinned-50th", "4-thread-pinned-" + percentilecompare, ]]

    write_it = header + first95 + second95 + third95

    write_csv_rows(starterpath + percentilecompare + "comparison", write_it)

    do_plotting(starterpath + percentilecompare + "comparison", percentilecompare)



if __name__ == "__main__":
    for compare in percentilecompares:
        full_script(compare)

