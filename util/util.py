import os
import subprocess as sp
import logging
import time
import random
import string
import sys
import pathlib as pth

root_dir = os.getcwd()
image_dir = os.path.join(root_dir, "images")
linux_dir = os.path.join(root_dir, "riscv-linux")
mnt = os.path.join(root_dir, "disk-mount")
commandScript = os.path.join(root_dir, "_command.sh")

# root_dir = pth.Path.cwd() 
# image_dir = root_dir / "images"
# linux_dir = root_dir / "riscv-linux"
# mnt = root_dir / "disk-mount"
# commandScript = root_dir / "_command.sh"

jlevel = "-j" + str(os.cpu_count())

# logging setup
def initLogging(args):
    rootLogger = logging.getLogger()
    rootLogger.setLevel(logging.NOTSET) # capture everything
    
    # Create a unique log name
    timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
    randname = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))
    # logPath = root_dir / ("logs" + timeline + "-" + \
    #         str(args.config_file.stem) + \
    #         "-" +  randname + ".log")
    logPath = os.path.join(root_dir, "logs", timeline + "-" + 
            os.path.splitext(os.path.basename(args.config_file))[0] +
            "-" +  randname + ".log")
    
    # formatting for log to file
    fileHandler = logging.FileHandler(str(logPath))
    logFormatter = logging.Formatter("%(asctime)s [%(funcName)-12.12s] [%(levelname)-5.5s]  %(message)s")
    fileHandler.setFormatter(logFormatter)
    fileHandler.setLevel(logging.NOTSET) # log everything to file
    rootLogger.addHandler(fileHandler)

    # log to stdout, without special formatting
    consoleHandler = logging.StreamHandler(stream=sys.stdout)
    if args.verbose:
        consoleHandler.setLevel(logging.NOTSET) # show only INFO and greater in console
    else:
        consoleHandler.setLevel(logging.INFO) # show only INFO and greater in console

    rootLogger.addHandler(consoleHandler)

# Run subcommands and handle logging etc.
# The arguments are identical to those for subprocess.call()
# level - The logging level to use
# check - Throw an error on non-zero return status?
def run(*args, level=logging.DEBUG, check=True, **kwargs):
    log = logging.getLogger()

    try:
        out = sp.check_output(*args, universal_newlines=True, stderr=sp.STDOUT, **kwargs)
        log.log(level, out)
    except sp.CalledProcessError as e:
        log.log(level, e.output)
        if check:
            raise

# Convert a linux configuration file to use an initramfs that points to the correct cpio
# This will modify linuxCfg in place
def convertInitramfsConfig(cfgPath, cpioPath):
    log = logging.getLogger()
    with open(cfgPath, 'at') as f:
        f.write("CONFIG_BLK_DEV_INITRD=y\n")
        f.write('CONFIG_INITRAMFS_SOURCE="' + cpioPath + '"\n')
 
# It's pretty easy to forget to update the linux config for initramfs-based
# workloads. We check here to make sure you've set the CONFIG_INITRAMFS_SOURCE
# option correctly. This only issues a warning right now because you might have
# a legitimate reason to point linux somewhere else (e.g. while debugging).
def checkInitramfsConfig(config):
    log = logging.getLogger()
    if config['rootfs-format'] == 'cpio':
       with open(config['linux-config'], 'rt') as f:
           linux_config = f.read()
           match = re.search(r'^CONFIG_INITRAMFS_SOURCE=(.*)$', linux_config, re.MULTILINE)
           if match:
               initramfs_src = os.path.normpath(os.path.join(linux_dir, match.group(1).strip('\"')))
               if initramfs_src != config['img']:
                   rootLogger.warning("WARNING: The workload linux config " + \
                   "'CONFIG_INITRAMFS_SOURCE' option doesn't point to this " + \
                   "workload's image:\n" + \
                   "\tCONFIG_INITRAMFS_SOURCE = " + initramfs_src + "\n" +\
                   "\tWorkload Image = " + config['img'] + "\n" + \
                   "You likely want to change this option to:\n" +\
                   "\tCONFIG_INITRAMFS_SOURCE=" + os.path.relpath(config['img'], linux_dir))
           else:
               rootLogger.warning("WARNING: The workload linux config doesn't include a " + \
               "CONFIG_INITRAMFS_SOURCE option, but this workload is " + \
               "using cpio for it's image.\n" + \
               "You likely want to change this option to:\n" + \
               "\tCONFIG_INITRAMFS_SOURCE=" + os.path.relpath(config['img'], linux_dir))
 
def genRunScript(command):
    with open(commandScript, 'w') as s:
        s.write("#!/bin/bash\n")
        s.write(command + "\n")
        s.write("poweroff\n")

    return commandScript

def toCpio(config, src, dst):
    log = logging.getLogger()

    run(['sudo', 'mount', '-o', 'loop', src, mnt])
    try:
        run("sudo find -print0 | sudo cpio --owner root:root --null -ov --format=newc > " + dst, shell=True, cwd=mnt)
    finally:
        run(['sudo', 'umount', mnt])


