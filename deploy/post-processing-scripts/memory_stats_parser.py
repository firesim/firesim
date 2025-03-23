#!/usr/bin/env python

# Post processing tools for memory models stats
# 1) Handle counter overflow
# 2) Removes samples that occur prior to HPM counters start

import csv
import os
import numpy as np
from post_process_utils import *

# Relative to workload directory
MEMORY_STATS_FILENAME = "memory_stats.csv"
# Relative to workload directory
OUTPUT_FILE = "memory_stats_processed.csv"
SUMMARY_FILENAME = "memory_stats.summary"
# Polling interval in target cycles
POLLING_PERIOD = 1000 * 1000 * 1000
# Width of the counters in the memory model
COUNTER_WIDTH = 32

# The size of one trasaction issued to the memory model
# There are some single beat transactions early on but not later
CACHE_LINE_SIZE = 64
AXI4_BUS_WIDTH_BYTES = 8


# Counter names that should be excluded if they have one of the following substrings
EXCLUDE_SUBSTRS = ["Addr_mask",
                   "Addr_offset",
                   "dramTimings_",
                   ]

# Counters that should be excluded if their names the match exactly
MATCH_EXACT = ["writeMaxReqs",
               "readMaxReqs",
               "readLatency",
               "writeLatency",
               "relaxFunctionalModel",
               "openPagePolicy",
               "llc_blockBits",
               "llc_setBits",
               "llc_wayBits",
               "backendLatency",
               ]

RANK_INSTRUMENTATION_PREFIX = "rankPower"
ACTIVE_RANKS_MASK_NAME = "rankAddr_mask"

# Looks at the rank address mask to calculate how many ranks were used.
# Encoded as (num ranks - 1)
def get_num_ranks(column_headers, first_row):
    num_ranks = 0
    for (i, column) in enumerate(column_headers):
        if column.find("allPreCycles") != -1:
            num_ranks += 1

    return num_ranks


# HPM counters dumps out the target cycle at which it first tared it's measurements
# We use this to coarsely align the memory models stats with the HPM counter stats
def get_application_start(hpm_counters_filename):
    with open (hpm_counters_filename, 'rb') as f:
        line = f.readline()
        assert line.find("##  T0CYCLES = ") != -1
        return int(line.strip().split("=")[1])

def unroll(num_rollovers, lsbs):
    return (long(1)<<COUNTER_WIDTH) * num_rollovers + int(lsbs)

def tare(args):
    num_rollovers, lsbs, tare_value = args
    return unroll(num_rollovers, lsbs) - tare_value

# Reject non-instrumentation columns
# Return a list of (column idx, name)
def get_valid_columns(header):
    valid_headers =  []
    valid_idxs=  []
    for i, column in enumerate(header):
        exclude = False
        for key in EXCLUDE_SUBSTRS:
            exclude = exclude or column.find(key) != -1
        for key in MATCH_EXACT:
            exclude = exclude or column == key
        if not exclude:
            valid_headers += [column]
            valid_idxs += [i]

    return (valid_idxs, valid_headers)

def get_processed_data(reader, valid_idxs, first_row, hpm_start_cycle):
    prev_data = [first_row[i] for i in valid_idxs]
    rollovers = [0] * len(valid_idxs)
    tare_values = [0] * len(valid_idxs)
    current_cycle = 0
    processed_data = []

    for row in reader:
        data = [int(row[idx]) for idx in valid_idxs]
        for i, (new, old) in enumerate(zip(data, prev_data)):
            if (int(new) < int(old)):
                rollovers[i] += 1
        if current_cycle <= hpm_start_cycle and (current_cycle + POLLING_PERIOD) > hpm_start_cycle:
            tare_values = list(map(tare, zip(rollovers, data, tare_values)))
        elif current_cycle > hpm_start_cycle:
            raw_processed = list(map(tare, zip(rollovers, data, tare_values)))
            processed_data += [[current_cycle - hpm_start_cycle] + raw_processed]
        prev_data = data
        current_cycle += POLLING_PERIOD;

    return processed_data

def get_rank_stat(data, key, num_ranks):
    return [data["{}_{}_{}".format(RANK_INSTRUMENTATION_PREFIX,i,key)] for i in xrange(num_ranks)]


def has_llc(headers):
    for key in headers:
        if key.find("llc_misses") is not -1:
            return True

    return False

def generate_llc_summary(data):
    total_reads = data["totalReads"]
    total_writes = data["totalWrites"]
    total_cycles = data["Cycle Count"]

    misses  =  data["llc_misses"]
    refills =  data["llc_refills"]
    writebacks =  data["llc_writebacks"]

    summary_string = ""
    summary_string +=  "LLC Summary:\n"
    summary_string +=  "    LLC Hit Rate (%%)         :  %6.3f\n" % (100 *  (1 - (misses)/float(total_reads + total_writes)))
    summary_string +=  "    LLC MPKC                 :  %6.3f\n" % (1000 * (misses)/float(total_cycles))
    summary_string +=  "    Miss : Writeback Ratio   :  %6.3f\n" % ((misses)/float(writebacks))
    return summary_string

def generate_summary(output_file, headers, data_vals, num_ranks):
    data = dict(zip(headers, data_vals))

    read_error = data["rrespError"]
    write_error = data["brespError"]

    total_cycles                  = data["Cycle Count"]
    total_reads                  = data["totalReads"]
    total_writes                 = data["totalWrites"]

    rank_pre_cycles  = get_rank_stat(data, "allPreCycles", num_ranks)
    rank_acts = get_rank_stat(data, "numACT",  num_ranks)
    rank_casr = get_rank_stat(data, "numCASR", num_ranks)
    rank_casw = get_rank_stat(data, "numCASW", num_ranks)
    total_acts = sum(rank_acts)
    total_casr = sum(rank_casr)
    total_casw = sum(rank_casw)


    summary_string = ""
    if read_error > 0:
        summary_string += "WARNING -- RRESP ERRORS      : {}\n\n".format(data["rrespError"])
    if write_error > 0:
        summary_string += "WARNING -- BRESP ERRORS      : {}\n\n".format(data["rrespError"])

    summary_string += "Number of Reads Serviced     :  {:.2E}\n".format(total_reads)
    summary_string += "Number of Writes Serviced    :  {:.2E}\n".format(total_writes)
    summary_string += "Average DRAM Read BW (GBPS)  :  %6.3f\n" % ((total_reads * CACHE_LINE_SIZE)/float(total_cycles))
    summary_string += "Average DRAM Write BW (GBPS) :  %6.3f\n" % ((total_writes * CACHE_LINE_SIZE)/float(total_cycles))
    summary_string += "AXI4 R Bus Utilization %%     :  %6.3f\n" % (
            (100 * (total_reads) * (CACHE_LINE_SIZE/AXI4_BUS_WIDTH_BYTES))/float(total_cycles))
    summary_string += "AXI4 W Bus Utilization %%     :  %6.3f\n" % (
            (100 * (total_writes) * (CACHE_LINE_SIZE/AXI4_BUS_WIDTH_BYTES))/float(total_cycles))

    if has_llc(headers):
        summary_string += generate_llc_summary(data)

    if num_ranks > 0:
        summary_string += "Row Buffer Hit %%             :  %6.3f\n" % (100 * (1 - ((total_acts)/float(total_casr + total_casw))))

    for i in xrange(num_ranks):
        summary_string += "RANK {} Summary :\n".format(i)
        summary_string += "    Row Buffer Hit %%         :  %6.3f\n" % (100 * (1 - ((rank_acts[i])/float(rank_casr[i] + rank_casw[i]))))
        summary_string += "    Cycles Idle %%            :  %6.3f\n" % (100 * (rank_pre_cycles[i])/float(total_cycles))


    print summary_string
    with open(output_file, 'wb') as f:
        f.write(summary_string)


def process_memory_stats(workload_dir, name):
    print "Parsing memory_stats.csv file for workload " + name
    memory_stats_filename = "{}/{}".format(workload_dir, MEMORY_STATS_FILENAME)
    hpm_counters_filename = "{}/{}".format(workload_dir, "hpm_data/hpm_counters0.out")
    hpm_start_cycle = get_application_start(hpm_counters_filename)
    output_dir = "{}/{}".format(workload_dir, POST_PROCESS_DIR)
    mkdir_p(output_dir)
    output_filename = "{}/{}".format(output_dir, OUTPUT_FILE)

    with open (memory_stats_filename, 'rb') as f:
        reader = csv.reader(f, delimiter=',',quotechar='|')
        header = next(reader)[:-1]
        first_row = next(reader)[:-1]
        num_ranks = get_num_ranks(header, first_row)

        # Exclude some garbage columns spat out by earlier models
        (valid_idxs, valid_column_header) = get_valid_columns(header)
        valid_column_header = ["Cycle Count"] + valid_column_header

        processed_data =  get_processed_data(reader, valid_idxs, first_row, hpm_start_cycle)
        #(derived_headers, derived_stats) = calculate_derived_stats(valid_column_header, processed_data)

    with open(output_filename, 'wb') as of:
        writer = csv.writer(of, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        # Just pass through the header directly
        # Add a cycle counter field to make it consistent with HPM
        writer.writerow(valid_column_header)
        for row in processed_data:
            writer.writerow(row)

    summary_file="{}/{}".format(output_dir, SUMMARY_FILENAME)
    generate_summary(summary_file, valid_column_header, processed_data[-1], num_ranks)

if __name__ == '__main__':
    workloads = get_args()
    for (wdir, name) in workloads:
        process_memory_stats(wdir, name)

