#!/usr/bin/env python 
# Christopher Celio

# parse a file of raw output data and generate a csv file
# looks for lines that have "## uarch_counter_name = ..."

import csv
import glob
from post_process_utils import *

# Relative to workload directory
COUNTER_FILES_GLOB = "hpm_data/hpm_counters*.out"

ARCH_COUNTER_LIST = [
    ("cycle" , "Cycle Count"),
    ("instret" , "Instructions Retired"),
    # the extra space at the end is CRITICAL
    ("hpmcounter3 " , "Loads"),
    ("hpmcounter4 " , "Stores"),
    ("hpmcounter5 " , "I$ Misses"),
    ("hpmcounter6 " , "D$ Misses"),
    ("hpmcounter7 " , "D$ Release"),
    ("hpmcounter8 " , "ITLB Misses"),
    ("hpmcounter9 " , "DTLB Misses"),
    ("hpmcounter10" , "L2TLB Misses"),
    ("hpmcounter11" , "Branches"),
    ("hpmcounter12" , "Branch mispredictions"),
    ("hpmcounter13" , "Load-use interlock"),
    ("hpmcounter14" , "I$ Blocked"),
    ("hpmcounter15" , "D$ Blocked")
    ]


def generate_summary(output_file, data):

    cycles = data["cycle"]
    instrets = data["instret"]
    total_cycle                  = data["cycle"][-1]
    total_instret                = long(data["instret"][-1])
    total_loads                  = data["hpmcounter3 "][-1]
    total_stores                 = data["hpmcounter4 "][-1]
    total_icache_misses          = data["hpmcounter5 "][-1]
    total_dcache_misses          = data["hpmcounter6 "][-1]
    total_itlb_misses            = data["hpmcounter8 "][-1]
    total_dtlb_misses            = data["hpmcounter9 "][-1]
    total_branches               = data["hpmcounter11"][-1]
    total_branch_mispredictions  = long(data["hpmcounter12"][-1])

    summary_string = ""
    summary_string += "Total Cycles        : {}\n".format(total_cycle)
    summary_string += "Total Instructions  : {}\n".format(total_instret)
    summary_string += "Total CPI           : %6.3f\n" % (total_cycle / float(total_instret))
    summary_string += "D$ MPKI             : %6.3f\n" % (1000 * (total_dcache_misses) / float(total_instret))
    summary_string += "I$ MPKI             : %6.3f\n" % (1000 * (total_icache_misses) / float(total_instret))
    summary_string += "D$ Miss %%           : %6.3f\n" % (100 * (total_dcache_misses / float(total_loads + total_stores)))
    summary_string += "ITLB MPKI           : %6.3f\n" % (1000 * (total_itlb_misses) / float(total_instret))
    summary_string += "DTLB MPKI           : %6.3f\n" % (1000 * (total_dtlb_misses) / float(total_instret))
    summary_string += "Branch Prediction %% : %6.3f\n" % (100 * (1 - (float(total_branch_mispredictions) / float(total_branches))))
    summary_string += "Branch MPKI         : %6.3f\n" % (1000 * (total_branch_mispredictions) / float(total_instret))

    print summary_string
    with open(output_file, 'wb') as f:
        f.write(summary_string)


# Spits out labels at the top of the csv file
def write_header(writer):
    labels = []
    for key, value in ARCH_COUNTER_LIST:
        labels += [value]
    writer.writerow(labels)

# Dumps the data
def write_body(writer, data):
    for i in range(len(data["cycle"])):
        row = ()
        for key, value in ARCH_COUNTER_LIST:
            if (data[key] is not None and len(data[key]) != 0) and i < len(data[key]):
                row += (data[key][i],)
            else:
                row += (0,)
        writer.writerow(row)

# Process each hpm_counters file for a workload
def process_workload(workload_dir, name):
    print "Parsing workload " + name
    counter_files = glob.glob("{}/{}".format(workload_dir, COUNTER_FILES_GLOB))
    for counter_file in counter_files:
        print "Parsing counter file: {}".format(counter_file.split('/')[-1])
        with open (counter_file, 'rb') as f:
            lines = f.readlines()

        data = {}
        for c, value in ARCH_COUNTER_LIST:
            data[c] = [int(x.strip().split("=")[1]) for x in lines if x.find("##  " + c) != -1]


        counter_shortname  = counter_file.split('/')[-1].strip(".out")
        output_dir = "{}/{}".format(workload_dir, POST_PROCESS_DIR)
        mkdir_p(output_dir)
        output_filename = "{}/{}.csv".format(output_dir, counter_shortname)
        with open(output_filename, 'wb') as csvfile:
            writer = csv.writer(csvfile, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
            write_header(writer)
            write_body(writer, data)

        summary_file="{}/{}.summary".format(output_dir, counter_shortname)
        generate_summary(summary_file, data)


if __name__ == '__main__':
    workloads = get_args()
    for (wdir, name) in workloads:
        process_workload(wdir, name)
