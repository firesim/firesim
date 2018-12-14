#!/usr/bin/env python3
"""
Tester for sw-manager.py.
To run all the sw_manager unit tests, do: ./run-tests.py test/*.json

The config(s) being tested should include a 'testing' attribute which includes the following feilds:
    {
        "buildTimeout" : N,     - Maximum expected run time of build command
        "runTimeout" : N,       - Maximum expected run time of launch command
        "refDir" : "dirPath"    - Directory containing reference outputs
                                  (relative to workdir). See util/check_output.py
                                  for details.
    }
"""
import sys
import json
import os
import argparse
import signal
import textwrap
import psutil
import multiprocessing as mp
from contextlib import contextmanager
from util.config import *
from util.util import *
from util.check_output import *
import sw_manager as sw

# Default timeouts (in seconds)
defBuildTimeout = 300
defRunTimeout = 300

# adapted from https://stackoverflow.com/questions/4675728/redirect-stdout-to-a-file-in-python/22434262#22434262
def fileno(file_or_fd):
    fd = getattr(file_or_fd, 'fileno', lambda: file_or_fd)()
    if not isinstance(fd, int):
        raise ValueError("Expected a file (`.fileno()`) or a file descriptor")
    return fd

# adapted from https://stackoverflow.com/questions/4675728/redirect-stdout-to-a-file-in-python/22434262#22434262
@contextmanager
def stdout_redirected(to=os.devnull, stdout=None):
    if stdout is None:
       stdout = sys.stdout

    stdout_fd = fileno(stdout)
    # copy stdout_fd before it is overwritten
    #NOTE: `copied` is inheritable on Windows when duplicating a standard stream
    with os.fdopen(os.dup(stdout_fd), 'wb') as copied: 
        stdout.flush()  # flush library buffers that dup2 knows nothing about
        try:
            os.dup2(fileno(to), stdout_fd)  # $ exec >&to
        except ValueError:  # filename
            with open(to, 'wb') as to_file:
                os.dup2(to_file.fileno(), stdout_fd)  # $ exec > to
        try:
            yield stdout # allow code to be run with the redirected stdout
        finally:
            # restore stdout to its previous value
            #NOTE: dup2 makes stdout_fd inheritable unconditionally
            stdout.flush()
            os.dup2(copied.fileno(), stdout_fd)  # $ exec >&copied

def runTimeout(func, timeout):
    def wrap(*args, **kwargs):
        p = mp.Process(target=func, args=args, kwargs=kwargs)
        p.start()
        p.join(timeout)
        if p.is_alive():
            # Kill all subprocesses (e.g. qemu)
            for child in psutil.Process(p.pid).children(recursive=True):
                child.kill()
            p.terminate()
            p.join()
            raise TimeoutError(func.__name__)
        elif p.exitcode != 0:
            raise ChildProcessError(func.__name__)

    return wrap

def main():
    parser = argparse.ArgumentParser(description="Tester for sw-manager.py")
    parser.add_argument('--workdir', help='Use a custom workload directory', default=os.path.join(root_dir, 'test'))
    parser.add_argument("-v", "--verbose",
        action="store_true", help="Print output of commands")
    parser.add_argument("configs", nargs="+", help="List of configs to test")
    args = parser.parse_args()

    if args.verbose:
        cmdOut = sys.stdout
    else:
        cmdOut = os.devnull

    cfgs = ConfigManager([args.workdir])

    suitePass = True
    for cfgPath in args.configs:
        print("Running " + cfgPath)
        setRunName(cfgPath, 'test')
        initLogging(args.verbose)

        cfgPath = os.path.join(root_dir, cfgPath)
        cfg = cfgs[cfgPath]
        testCfg = cfg['testing']
        
        if 'buildTimeout' not in testCfg:
            testCfg['buildTimeout'] = defBuildTimeout
        if 'runTimeout' not in testCfg:
            testCfg['runTimeout'] = defRunTimeout

        cmdArgs = argparse.Namespace(config_file=cfgPath, job='all', initramfs=False, spike=False)
        try:
            with stdout_redirected(cmdOut):
                runTimeout(sw.handleBuild, testCfg['buildTimeout'])(cmdArgs, cfgs)
                runTimeout(sw.handleLaunch, testCfg['runTimeout'])(cmdArgs, cfgs)
                
            refPath = os.path.join(cfg['workdir'], testCfg['refDir'])
            testPath = os.path.join(res_dir, getRunName())
            diff = cmpOutput(testPath, refPath)
            if diff is not None:
                suitePass = False
                print("Test " + os.path.basename(cfgPath) + " failure: output does not match reference")
                print(textwrap.indent(diff, '\t'))
                continue

        except TimeoutError as e:
            suitePass = False
            print("Test " + os.path.basename(cfgPath) + " failure: Timeout")
            continue

        except ChildProcessError as e:
            suitePass = False
            if e.args[0] == "handleBuild":
                print("Test " + os.path.basename(cfgPath) + " failure: Exception while building")
            elif e.args[0] == "handleLaunch":
                print("Test " + os.path.basename(cfgPath) + " failure: Exception while running")
            continue

        except Exception as e:
            suitePass = False
            print("Test " + os.path.basename(cfgPath) + " failure: Exception encountered")
            print("\t" + repr(e))
            continue

        print("Success")

    if suitePass:
        print("Suite Success")
        sys.exit(0)
    else:
        print("Suite Failure")
        sys.exit(1)

main()
