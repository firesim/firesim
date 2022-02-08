#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import shutil
import time

BUILD_DIR = 'build'
MOUNT_POINT = BUILD_DIR + "/mount_point"
EXT_TYPE = "ext2"
INIT_SCRIPT_NAME = '/etc/init.d/S99run'

init_script_head = """#!/bin/sh
#

SYSLOGD_ARGS=-n
KLOGD_ARGS=-n

start() {
"""

init_script_tail = """
    sync
    poweroff -f
}

case "$1" in
  start)
	start
	;;
  stop)
	#stop
	;;
  restart|reload)
	start
	;;
  *)
	echo "Usage: $0 {start|stop|restart}"
	exit 1
esac

exit
"""

def copy_base_rootfs(base_rootfs, dest):
    try:
        os.makedirs(BUILD_DIR)
    except OSError:
        pass
    print("Copying base rootfs {} to {}".format(base_rootfs, dest))
    shutil.copy2(base_rootfs, dest)

def mount_rootfs(rootfs):
    try:
        os.makedirs(MOUNT_POINT)
    except OSError:
        pass
    rc = subprocess.check_output(["sudo", "mount", "-t", EXT_TYPE, rootfs, MOUNT_POINT])

def cp_target(src, target_dest):
    print("Copying src: {} to {} in target filesystem.".format(src, target_dest))
    if not os.path.isdir(target_dest):
        dirname = os.path.dirname(target_dest)
    else:
        dirname = target_dest

    subprocess.check_output(["sudo", "mkdir", "-p", MOUNT_POINT + "/" + dirname])
    rc = subprocess.check_output(["sudo", "cp", "-dpR", src, "-T", MOUNT_POINT + "/" + target_dest])

def chmod_target(permissions, target_dest):
    subprocess.check_output(["sudo", "chmod", permissions, MOUNT_POINT + "/" + target_dest])
    subprocess.check_output(["sudo", "chown", "root:root", MOUNT_POINT + "/" + target_dest])

def copy_files_to_mounted_fs(overlay, deliver_dir, files):
    for f in files:
        src = overlay + "/" + f
        target_local_dest = "/" + deliver_dir + "/" + f
        cp_target(src, target_local_dest)

def generate_init_script(command):
    print("Creating init script with command:\n    " + command)
    init_script_body = init_script_head + "    " + command + init_script_tail

    temp_script = BUILD_DIR + "/temp"
    with open(temp_script, 'w') as f:
        f.write(init_script_body)

    cp_target(temp_script, INIT_SCRIPT_NAME)
    chmod_target('755', INIT_SCRIPT_NAME)

def unmount_rootfs():
    time.sleep(0.2)
    rc = subprocess.check_output(["sudo", "umount", MOUNT_POINT])

class Workload:

    def __init__(self, name, deliver_dir, files, command, args, outputs):
        self.name = name
        self.deliver_dir = deliver_dir
        self.files = files
        self.command = command
        for arg in args:
           self.command += " " + arg
        self.outputs = outputs

    def generate_rootfs(self, base_rootfs, overlay, gen_init, output_dir):
        print("\nGenerating a Rootfs image for " + self.name)
        dest_rootfs = output_dir + "/" + self.name + "." + EXT_TYPE

        copy_base_rootfs(base_rootfs, dest_rootfs)
        mount_rootfs(dest_rootfs)
        copy_files_to_mounted_fs(overlay, self.deliver_dir, self.files)
        if gen_init:
            generate_init_script(self.command)
        unmount_rootfs()

    def __str__(self):
        return("""
Name: {}
    Files : {}
    Command: {}
    """.format(self.name, str(self.files), self.command))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='FireSim Benchmark RootFS Generator.')
    parser.add_argument('-w', '--benchmark', type=str, help='Benchmark JSON.')
    parser.add_argument('-s', '--overlay-dir', type=str,  dest='overlay_dir',
                        help='Overlay location that files specified in the json can be found.')
    parser.add_argument('-o', '--output-dir',
                        dest='output_dir',
                        type=str,
                        help='Location to store filesystem images. Default: ./<benchmark-name>/')
    parser.add_argument('-r', '--run-on-init',
                        dest='init',
                        action='store_true',
                        help='Emits an init script to run the workload and power off the instance.')
    parser.add_argument('-b', '--base',  type=str, help='The base filesystem image')
    args = parser.parse_args()

    with open(args.benchmark) as jsonf:
        benchmark_config = json.load(jsonf)

    benchmark_name = benchmark_config["benchmark_name"]
    common_args = benchmark_config["common_args"]
    common_files = benchmark_config["common_files"]
    common_outputs = benchmark_config["common_outputs"]
    overlay_dir = args.overlay_dir
    deliver_dir = benchmark_config["deliver_dir"]
    output_dir = args.output_dir if args.output_dir is not None else benchmark_name

    workloads = []
    for workload_def in benchmark_config["workloads"]:

        workloads.append(Workload(workload_def["name"],
                                  deliver_dir,
                                  workload_def["files"] + common_files,
                                  workload_def["command"],
                                  common_args,
                                  workload_def["outputs"] + common_outputs))


    subprocess.check_output(["mkdir", "-p", output_dir])
    for workload in workloads:
        workload.generate_rootfs(args.base, overlay_dir, args.init, output_dir)
