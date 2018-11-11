import os
import subprocess as sp
import logging
import time
import random
import string
import sys

root_dir = os.getcwd()
workload_dir = os.path.join(root_dir, "workloads")
image_dir = os.path.join(root_dir, "images")
linux_dir = os.path.join(root_dir, "riscv-linux")
mnt = os.path.join(root_dir, "disk-mount")

jlevel = "-j" + str(os.cpu_count())

# logging setup
def initLogging(args):
    rootLogger = logging.getLogger()
    rootLogger.setLevel(logging.NOTSET) # capture everything
    
    # Create a unique log name
    timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
    randname = ''.join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))
    logPath = os.path.join(root_dir, "logs", timeline + "-" + 
            os.path.splitext(os.path.basename(args.config_file))[0] +
            "-" +  randname + ".log")

    # formatting for log to file
    fileHandler = logging.FileHandler(logPath)
    logFormatter = logging.Formatter("%(asctime)s [%(funcName)-12.12s] [%(levelname)-5.5s]  %(message)s")
    fileHandler.setFormatter(logFormatter)
    fileHandler.setLevel(logging.NOTSET) # log everything to file
    rootLogger.addHandler(fileHandler)

    # log to stdout, without special formatting
    consoleHandler = logging.StreamHandler(stream=sys.stdout)
    consoleHandler.setLevel(logging.INFO) # show only INFO and greater in console
    rootLogger.addHandler(consoleHandler)

# Run subcommands and handle logging etc.
# The arguments are identical to those for subprocess.call()
# level - The logging level to use
# check - Throw an error on non-zero return status?
def run(*args, level=logging.DEBUG, check=True, **kwargs):
    log = logging.getLogger()

    try:
        out = sp.check_output(*args, universal_newlines=True, **kwargs)
        # out = sp.check_output(*args, **kwargs)
        log.log(level, out)
    except sp.CalledProcessError as e:
        log.log(level, e.output)
        if check:
            raise
