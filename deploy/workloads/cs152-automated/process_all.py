#!/usr/bin/env python
from post_process_utils import *
import hpm_data_parser
import memory_stats_parser
import glob

if __name__ == '__main__':
    workloads = get_args()
    for (wdir, name) in workloads:
        # This is for the whole execution
        memory_stats_parser.process_memory_stats(wdir, name)
        hpm_data_parser.process_workload(wdir, name)

        # Re-process the memory stats for sub-sections of the execution that apply
        counter_files = glob.glob("{}/{}".format(wdir, hpm_data_parser.COUNTER_FILES_GLOB))
        for fn in counter_files:
            memory_stats_parser.process_memory_stats(wdir, name, fn)


