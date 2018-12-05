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

jlevel = "-j" + str(os.cpu_count())

# logging setup
def initLogging(args):
    rootLogger = logging.getLogger()
    rootLogger.setLevel(logging.NOTSET) # capture everything
    
    # Create a unique log name
    timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
    randname = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))

    logPath = os.path.join(root_dir, "logs", os.path.splitext(os.path.basename(args.config_file))[0] +
            "-" + timeline + "-" +
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


