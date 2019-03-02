#!/usr/bin/env python
import os
import errno
import argparse

# The subdirectory into which post processed files should be droped
POST_PROCESS_DIR = "post_processed"

def mkdir_p(dirname):
    try:
        os.makedirs(dirname)
    except OSError as e:
        if e.errno == errno.EEXIST:
            pass
        else:
            raise

def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('-b', '--benchmark', dest='bmark_dir',
                        help='A subdirectory with the output from a benchmark run')
    parser.add_argument('-w', '--workload', dest='workload_dir',
                      help='A subdirectory with the output from a single workload of a benchmark')
    args = parser.parse_args()

    if args.bmark_dir:
        base_dir = args.bmark_dir
        workload_names = os.listdir(args.bmark_dir)
        workloads = [(base_dir + '/' + name, name) for name in workload_names]

    elif args.workload_dir:
        workload_dir = args.workload_dir.strip('/')
        workload_name = workload_dir.split('/')[-1]
        workloads = [(workload_dir, workload_name)]

    else:
        parser.error('Pass a benchmark directory (-b) or workload directory (-w)')

    return workloads
