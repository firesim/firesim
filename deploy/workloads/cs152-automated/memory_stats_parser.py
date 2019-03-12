#!/usr/bin/env python

# Post processing tools for memory models stats
# 1) Handle counter overflow
# 2) Removes samples that occur prior to HPM counters start

import csv
import sys
import os
import numpy as np
import re
from post_process_utils import *

# Relative to workload directory
MEMORY_STATS_FILENAME = "memory_stats.csv"
UARTLOG_FILENAME = "uartlog"
# Relative to workload directory
OUTPUT_FILE = "memory_stats_processed"
SUMMARY_FILENAME = "memory_stats"
# Width of the counters in the memory model
COUNTER_WIDTH = 32

# The size of one trasaction issued to the memory model
# There are some single beat transactions early on but not later
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

def get_profile_interval(uartlog_filename):
    with open(uartlog_filename, 'rb') as f:
        lines = f.readlines()
        for line in lines:
            m = re.search('\+profile-interval=([-0-9]*)', line)
            if m:
                interval = int(m.group(1))
                if interval == -1:
                    print("Memory model profiling was disabled for this workload")
                    sys.exit(0)
                else:
                    return interval

    raise Exception('Could determine the profile-interval')

# Looks up the number of active ranks in this particular simulation by parsing
# the uartlog
def get_num_ranks(uartlog_filename):
    num_hw_ranks = 0
    with open(uartlog_filename, 'rb') as f:
        lines = f.readlines()
        for line in lines:
            m = re.search('\+mm_rankAddr_mask=([-0-9]*)', line)
            if m:
                rank_mask = int(m.group(1))
                return (rank_mask + 1)

    return 0

# HPM counters dumps out the target cycle at which it first tared it's measurements
# We use this to coarsely align the memory models stats with the HPM counter stats
def get_application_interval(hpm_counters_filename):
    with open (hpm_counters_filename, 'rb') as f:
        line = f.readline()
        assert line.find("##  T0CYCLES = ") != -1
        start_cycle = int(line.strip().split("=")[1])

        last_cycle_line = ""
        for line in f.readlines() :
            if line.find("##  Cycles = ") != -1 :
                last_cycle_line = line

        execution_length = int(last_cycle_line.strip().split("=")[1])
        return (start_cycle, start_cycle + execution_length)

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

def get_processed_data(reader, valid_idxs, first_row, profile_interval, hpm_start_cycle, hpm_end_cycle):
    prev_data = [first_row[i] for i in valid_idxs]
    rollovers = [0] * len(valid_idxs)
    tare_values = [0] * len(valid_idxs)
    current_cycle = 0
    processed_data = []
    if hpm_start_cycle is None:
        hpm_start_cycle = 0
        hpm_end_cycle = sys.maxint

    assert (hpm_start_cycle  + profile_interval) < hpm_end_cycle

    for row in reader:
        data = [int(row[idx]) for idx in valid_idxs]
        for i, (new, old) in enumerate(zip(data, prev_data)):
            if (int(new) < int(old)):
                rollovers[i] += 1
        if current_cycle <= hpm_start_cycle and (current_cycle + profile_interval) > hpm_start_cycle:
            tare_values = list(map(tare, zip(rollovers, data, tare_values)))
        elif current_cycle > (hpm_end_cycle + profile_interval/2):
            break
        elif current_cycle > hpm_start_cycle:
            raw_processed = list(map(tare, zip(rollovers, data, tare_values)))
            processed_data += [[current_cycle - hpm_start_cycle] + raw_processed]
        prev_data = data
        current_cycle += profile_interval;

    return processed_data

def get_rank_stat(data, key, num_ranks):
    return [data["{}_{}_{}".format(RANK_INSTRUMENTATION_PREFIX,i,key)] for i in xrange(num_ranks)]


def has_llc(headers):
    for key in headers:
        if key.find("llc_misses") is not -1:
            return True

    return False

def generate_llc_summary(data):

    total_cycles= data["Cycle Count"]
    total_reads = data["totalReads"]
    total_read_beats = data["totalReadBeats"]
    total_writes = data["totalWrites"]
    total_write_beats = data["totalWriteBeats"]

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

    total_cycles= data["Cycle Count"]
    total_reads = data["totalReads"]
    total_read_beats = data["totalReadBeats"]
    total_writes = data["totalWrites"]
    total_write_beats = data["totalWriteBeats"]

    rank_pre_cycles  = get_rank_stat(data, "allPreCycles", num_ranks)
    rank_acts = get_rank_stat(data, "numACT",  num_ranks)
    rank_casr = get_rank_stat(data, "numCASR", num_ranks)
    rank_casw = get_rank_stat(data, "numCASW", num_ranks)
    total_acts = sum(rank_acts)
    total_casr = sum(rank_casr)
    total_casw = sum(rank_casw)


    summary_string = ""
    #if read_error > 0:
    #    summary_string += "WARNING -- RRESP ERRORS      : {}\n\n".format(data["rrespError"])
    #if write_error > 0:
    #    summary_string += "WARNING -- BRESP ERRORS      : {}\n\n".format(data["rrespError"])

    summary_string += "Number of Reads Serviced     :  {:.2E}\n".format(total_reads)
    summary_string += "Number of Writes Serviced    :  {:.2E}\n".format(total_writes)
    summary_string += "AXI4 R Bus Utilization %%     :  %6.3f\n" % (
            (100 * (total_read_beats * AXI4_BUS_WIDTH_BYTES))/float(total_cycles))
    summary_string += "AXI4 W Bus Utilization %%     :  %6.3f\n" % (
            (100 * (total_write_beats * AXI4_BUS_WIDTH_BYTES))/float(total_cycles))

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


def process_memory_stats(workload_dir, name, hpm_counters_filename = None):
    print "Parsing memory_stats.csv file for workload " + name
    if hpm_counters_filename:
        print "   WRT to hpm_counters file: " + hpm_counters_filename

    counter_suffix = "-" + hpm_counters_filename.split("/")[-1] if hpm_counters_filename else ""

    memory_stats_filename = "{}/{}".format(workload_dir, MEMORY_STATS_FILENAME)
    uartlog_filename = "{}/{}".format(workload_dir, UARTLOG_FILENAME)
    if hpm_counters_filename:
        (hpm_start_cycle, hpm_end_cycle) = get_application_interval(hpm_counters_filename)
        print "   Start Cycle: {}, End Cycle: {}".format(hpm_start_cycle, hpm_end_cycle)
    else :
        hpm_start_cycle = None
        hpm_end_cycle = None

    profile_interval = get_profile_interval(uartlog_filename)

    output_dir = "{}/{}".format(workload_dir, POST_PROCESS_DIR)
    mkdir_p(output_dir)
    output_filename = "{}/{}{}.csv".format(output_dir, OUTPUT_FILE, counter_suffix)

    with open (memory_stats_filename, 'rb') as f:
        reader = csv.reader(f, delimiter=',',quotechar='|')
        header = next(reader)[:-1]
        first_row = next(reader)[:-1]
        num_ranks = get_num_ranks(uartlog_filename)

        # Exclude some garbage columns spat out by earlier models
        (valid_idxs, valid_column_header) = get_valid_columns(header)
        valid_column_header = ["Cycle Count"] + valid_column_header

        processed_data =  get_processed_data(reader, valid_idxs, first_row, profile_interval, hpm_start_cycle, hpm_end_cycle)
        #(derived_headers, derived_stats) = calculate_derived_stats(valid_column_header, processed_data)

    with open(output_filename, 'wb') as of:
        writer = csv.writer(of, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        # Just pass through the header directly
        # Add a cycle counter field to make it consistent with HPM
        writer.writerow(valid_column_header)
        for row in processed_data:
            writer.writerow(row)

    counter_suffix = "-" + hpm_counters_filename.split("/")[-1] if hpm_counters_filename else ""
    summary_file="{}/{}{}.summary".format(output_dir, SUMMARY_FILENAME, counter_suffix)

    generate_summary(summary_file, valid_column_header, processed_data[-1], num_ranks)

if __name__ == '__main__':
    workloads = get_args()
    for (wdir, name) in workloads:
        process_memory_stats(wdir, name)

