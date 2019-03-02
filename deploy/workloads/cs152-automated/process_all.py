#!/usr/bin/env python
from post_process_utils import *
import hpm_data_parser
import memory_stats_parser

if __name__ == '__main__':
    workloads = get_args()
    for (wdir, name) in workloads:
        memory_stats_parser.process_memory_stats(wdir, name)
        hpm_data_parser.process_workload(wdir, name)

