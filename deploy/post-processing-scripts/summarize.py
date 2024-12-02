#! /usr/bin/env python

# Author: Christopher Celio
from functools import partial
import numpy as np
import pylab
import csv
import optparse
import os
from data_parser import arch_counter_list

bmark_dirs = ["csv_data/spec-fcfs-open-counters", "csv_data/spec06-enable-128L2TLB", "csv_data/spec06-enable-32K"]

bmark_counter_data = {}

for bmark in bmark_dirs:
    workload_dirs = os.listdir(bmark)
    counter_data = {}
    for workload in workload_dirs:
        with open(bmark + "/" + workload + "/hpm_counters.csv", 'rb') as csv_file:
            reader = csv.reader(csv_file, delimiter=',',quotechar='|')
            labels = next(reader)
            lastrow = None
            for lastrow in reader: pass
            data = {}
            for idx, field in enumerate(labels):
                data[field] = int(lastrow[idx])
            counter_data[workload] = data

    bmark_counter_data[bmark] = counter_data

def get_data(data, field):
    return data[field]

def get_rate(data, numerators, denominators):
    numer = reduce(lambda x, y: x + y, [data[field] for field in numerators])
    denom = reduce(lambda x, y: x + y, [data[field] for field in denominators])
    return (100 * numer/float(denom))

def get_cycle_normalized(data, numerator, scale):
    return (scale * data[numerator]/float(data["Cycle Count"]))

def get_MPKI(data, numerator):
    return (1000 * data[numerator]/float(data["Instructions Retired"]))

get_icache_mpki = partial(get_MPKI, numerator = "I$ Misses")
get_dcache_mpki = partial(get_MPKI, numerator = "D$ Misses")
get_dcache_rpki = partial(get_MPKI, numerator = "D$ Release")
get_itlb_mpki = partial(get_MPKI, numerator = "ITLB Misses")
get_dtlb_mpki = partial(get_MPKI, numerator = "DTLB Misses")

get_icache_mpkc = partial(get_cycle_normalized, numerator = "I$ Misses", scale = 1000)
get_dcache_mpkc = partial(get_cycle_normalized, numerator = "D$ Misses", scale = 1000)
get_dcache_rpkc = partial(get_cycle_normalized, numerator = "D$ Release", scale = 1000)

def get_CPI(data):
    return int(data["Cycle Count"])/float(data["Instructions Retired"])

get_dcache_miss_rate = partial(get_rate, numerators = ["D$ Misses"], denominators = ["Loads", "Stores"])
get_dcache_stall_percent = partial(get_cycle_normalized, numerator =  "D$ Blocked", scale = 100)


metrics = [
        ("Cycle Count", partial(get_data, field = "Cycle Count")),
        ("Instructions Retired", partial(get_data, field = "Instructions Retired")),
        ("CPI", get_CPI),
        ("Loads", partial(get_data, field = "Loads")),
        ("Stores", partial(get_data, field = "Stores")),
        ("D$ MPKI", get_dcache_mpki),
        ("D$ RPKI", get_dcache_rpki),
        ("D$ Miss %", get_dcache_miss_rate),
        ("DTLB MPKI", get_dtlb_mpki),
        ("D$ Stall %", get_dcache_stall_percent),
        ("I$ MPKI", get_icache_mpki),
        ("ITLB MPKI", get_itlb_mpki),
        ("D$ MPKC", get_dcache_mpkc),
        ("D$ RPKC", get_dcache_rpkc),
        ("I$ MPKC", get_icache_mpkc)
        ]

with open("summary.csv",'wb') as csvfile:
    writer = csv.writer(csvfile, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
    workload_dirs = os.listdir(bmark_dirs[1])

    labels = ["Metric"]
    for workload in workload_dirs:
        for bmark in bmark_dirs:
            labels += [bmark.split('/')[-1] + "_" + workload]
    writer.writerow(labels)

    for (metric_name, metric_func) in metrics:
        row_data = [metric_name]
        for workload_name  in workload_dirs:
            for bmark in bmark_dirs:
                if workload_name in bmark_counter_data[bmark]:
                    data = bmark_counter_data[bmark][workload_name]
                    row_data.append(str(metric_func(data)))
                else:
                    row_data.append("N/A")
        writer.writerow(row_data)
