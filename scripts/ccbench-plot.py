#!/usr/bin/env python

import matplotlib
matplotlib.use('PDF')
import matplotlib.pyplot as plt
from scipy.interpolate import interp1d
import numpy as np
import sys
import argparse
import re
from collections import defaultdict

ATARG_RE = re.compile(r"@(\w+)=\[(.+)\]")
DATACELL_RE = re.compile(r"(\w+):\[(.+)\]")

def parse_report(f):
    data = defaultdict(list)
    for line in f:
        if line[0] == '@':
            match = ATARG_RE.match(line.strip())
            name, value = match.groups()
            data[name] = value
        else:
            for cell in line.split(","):
                match = DATACELL_RE.match(cell.strip())
                name, value = match.groups()
                data[name].append(value)
    return data

def main():
    parser = argparse.ArgumentParser(description="Parse ccbench report")
    parser.add_argument("--wordsize", type=int, default=8,
                        help="Number of bytes in a word")
    parser.add_argument("reportfile", help="Input report file")
    parser.add_argument("plotfile", help="Output plot PDF")
    args = parser.parse_args()

    sizes = None
    times = None
    ndatapoints = None

    with open(args.reportfile) as f:
        data = parse_report(f)
        sizes = [int(size) * args.wordsize for size in data["AppSize"]]
        times = [float(time) for time in data["Time"]]
        ndatapoints = int(data["NumDataPointsPerSet"])

    labels = ["Random", "Unit Stride", "Cache Line Stride"]

    for start, label in zip(range(0, len(times), ndatapoints), labels):
        end = start + ndatapoints
        x = sizes[start:end]
        y = times[start:end]
        f = interp1d(x, y)
        xnew = np.logspace(np.log10(x[0]+1), np.log10(x[-1]-1))
        plt.scatter(x, y)
        plt.plot(xnew, f(xnew), label=label)

    plt.xscale("log")
    plt.yscale("log")
    plt.xlabel("Array Size (bytes)")
    plt.ylabel(data["TimeUnits"][0])
    plt.legend()
    plt.savefig(args.plotfile)

if __name__ == "__main__":
    main()
