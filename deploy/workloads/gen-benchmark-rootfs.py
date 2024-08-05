#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import shutil
import time
from contextlib import contextmanager
import errno

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

sudoCmd = ["/usr/bin/sudo"]
pwdlessSudoCmd = []  # set if pwdless sudo is enabled

def runnableWithSudo(cmd):
    global sudoCmd
    return subprocess.run(sudoCmd + ['-ln', cmd], stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL).returncode == 0

if runnableWithSudo('true'):
    # User has passwordless sudo available
    pwdlessSudoCmd = sudoCmd

def existsAndRunnableWithSudo(cmd):
    global sudoCmd
    return os.path.exists(cmd) and runnableWithSudo(cmd)

def run(*args, check=True, **kwargs):
    """Run subcommands and handle logging etc. The arguments are identical to those for subprocess.call().
        check - Throw an error on non-zero return status"""

    if isinstance(args[0], str):
        prettyCmd = args[0]
    else:
        prettyCmd = ' '.join([str(a) for a in args[0]])

    p = subprocess.Popen(*args, universal_newlines=True, stderr=subprocess.STDOUT, stdout=subprocess.PIPE, **kwargs)
    for line in iter(p.stdout.readline, ''):
        print(line.strip())
    p.wait()

    if check and p.returncode != 0:
        raise subprocess.CalledProcessError(p.returncode, prettyCmd)

    return p

def waitpid(pid):
    """This is like os.waitpid, but it works for non-child processes"""
    done = False
    while not done:
        try:
            os.kill(pid, 0)
        except OSError as err:
            if err.errno == errno.ESRCH:
                done = True
                break
        time.sleep(0.25)

# similar to https://github.com/firesim/FireMarshal/blob/master/wlutil/wlutil.py
@contextmanager
def mount_rootfs(rootfs):
    global sudoCmd
    global pwdlessSudoCmd

    try:
        os.makedirs(MOUNT_POINT)
    except OSError:
        pass

    ret = run(["mountpoint", MOUNT_POINT], check=False).returncode
    assert ret == 1, f"{MOUNT_POINT} already mounted. Somethings wrong"

    uid = subprocess.run(['id', '-u'], capture_output=True, text=True).stdout.strip()
    gid = subprocess.run(['id', '-g'], capture_output=True, text=True).stdout.strip()

    if pwdlessSudoCmd:
        # use faster mount without firesim script since we have pwdless sudo
        run(pwdlessSudoCmd + ["mount", "-t", EXT_TYPE, rootfs, MOUNT_POINT])
        run(pwdlessSudoCmd + ["chown", "-R", f"{uid}:{gid}", MOUNT_POINT])
        try:
            yield MOUNT_POINT
        finally:
            run(pwdlessSudoCmd + ['umount', MOUNT_POINT])
    else:
        # use either firesim-*mount* cmds if available/useable or default to guestmount (slower but reliable)
        fsimMountCmd = '/usr/local/bin/firesim-mount-with-uid-gid'
        fsimUnmountCmd = '/usr/local/bin/firesim-unmount'

        if existsAndRunnableWithSudo(fsimMountCmd) and existsAndRunnableWithSudo(fsimUnmountCmd):
            run(sudoCmd + [fsimMountCmd, rootfs, MOUNT_POINT, uid, gid])
            try:
                yield MOUNT_POINT
            finally:
                run(sudoCmd + [fsimUnmountCmd, MOUNT_POINT])
        else:
            pidPath = './guestmount.pid'
            run(['guestmount', '--pid-file', pidPath, '-o', f'uid={uid}', '-o', f'gid={gid}', '-a', rootfs, '-m', '/dev/sda', MOUNT_POINT])
            try:
                with open(pidPath, 'r') as pidFile:
                    mntPid = int(pidFile.readline())
                yield MOUNT_POINT
            finally:
                run(['guestunmount', MOUNT_POINT])
                os.remove(pidPath)

            # There is a race-condition in guestmount where a background task keeps
            # modifying the image for a period after unmount. This is the documented
            # best-practice (see man guestmount).
            waitpid(mntPid)

def cp_target(src, target_dest):
    print("Copying src: {} to {} in target filesystem.".format(src, target_dest))
    if not os.path.isdir(target_dest):
        dirname = os.path.dirname(target_dest)
    else:
        dirname = target_dest

    subprocess.check_output(["mkdir", "-p", MOUNT_POINT + "/" + dirname])
    rc = subprocess.check_output(["cp", "-dpR", src, "-T", MOUNT_POINT + "/" + target_dest])

def chmod_target(permissions, target_dest):
    subprocess.check_output(["chmod", permissions, MOUNT_POINT + "/" + target_dest])

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
        with mount_rootfs(dest_rootfs):
            copy_files_to_mounted_fs(overlay, self.deliver_dir, self.files)
            if gen_init:
                generate_init_script(self.command)

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
