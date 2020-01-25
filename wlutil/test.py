#!/usr/bin/env python3
import sys
import argparse
import pathlib as pl
import difflib
import os
from contextlib import contextmanager
import pathlib
import re
import multiprocessing as mp
import logging
import traceback
import textwrap
import psutil
from enum import Enum
from .wlutil import *
from .build import *
from .launch import *
 
testResult = Enum('testResult', ['success', 'failure', 'skip'])

# Default timeouts (in seconds)
defBuildTimeout = 2400 
defRunTimeout =  2400

# Compares two runOutput directories. Returns None if they match or a message
# describing the difference if they don't.
#   - Directory structures are compared directly (same folders in the same
#     places). Files/Directories in testDir that don't exist in refDir are
#     ignored (refDir is a subset of testDir).
#   - Regular files are compared using standard diff (reports line # of
#     difference, must match exactly)
#   - Files named "uartlog" in the reference output need only match a subset of
#     the test output (the entire reference uartlog contents must exist somewhere
#     in the test output).
def cmpOutput(testDir, refDir, strip=False):
    testDir = pl.Path(testDir)
    refDir = pl.Path(refDir)
    if not refDir.exists():
        return "reference directory: " + str(refDir) + " does not exist"

    for rPath in refDir.glob("**/*"):
        # tPath = testDir / pl.Path(*rPath.parts[1:])
        tPath = testDir / rPath.relative_to(refDir)
        if not tPath.exists():
            return "Missing file or directory: " + str(tPath)

        if rPath.is_file():
            # Regular file, should match exactly
            with open(str(rPath), 'r') as rFile:
                with open(str(tPath), 'r') as tFile:
                    if rPath.name == "uartlog":
                        rLines = rFile.readlines()
                        tLines = tFile.readlines()
                        
                        matcher = difflib.SequenceMatcher(None, rLines, tLines)
                        m = matcher.find_longest_match(0, len(rLines), 0, len(tLines))
                        if m.size != len(rLines):
                            if m.size == 0:
                                return str(rPath) + " and " + str(tPath) + " do not match"
                            else:
                                return str(tPath) + " matches only at " + \
                                       str(rPath) + ":" + str(m.a) + "," + str(m.a + m.size) + "\n" + \
                                       "".join(rLines[m.a : m.a + m.size])
                    else:
                        # I'm not 100% sure what will happen with a binary file
                        diffString = "".join(difflib.unified_diff(rFile.readlines(),
                                tFile.readlines(), fromfile=str(rPath), tofile=str(tPath)))
                        if diffString is not "":
                            return diffString

    return None

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

# Fedora run output can be tricky to compare due to lots of non-deterministic
# output (e.g. timestamps, pids) This function takes the entire uartlog from a
# fedora run and returns only the output of auto-run scripts
def stripFedoraUart(lines):
    stripped = ""
    pat = re.compile(".*firesim.sh\[\d*\]: (.*\n)")
    for l in lines:
        match = pat.match(l)
        if match:
            stripped += match.group(1)

    return stripped

def stripBrUart(lines):
    stripped = ""
    inBody = False
    for l in lines:
        if not inBody:
            if re.match("launching firesim workload run/command", l):
                inBody = True
        else:
            if re.match("firesim workload run/command done", l):
                break
            stripped += l

    return stripped
          
def stripUartlog(config, outputPath):
    outDir = pathlib.Path(outputPath)
    for uartPath in outDir.glob("**/uartlog"):
        with open(str(uartPath), 'r', errors='ignore') as uFile:
            uartlog = uFile.readlines()

        if 'distro' in config:
            if config['distro'] == 'fedora':
                strippedUart = stripFedoraUart(uartlog)
            elif config['distro'] == 'br':
                strippedUart = stripBrUart(uartlog)
            else:
                strippedUart = "".join(uartlog)
        else:
            strippedUart = "".join(uartlog)

        with open(str(uartPath), 'w') as uFile:
            uFile.write(strippedUart)

# Build and run a workload and compare results against the testing spec
# ('testing' field in config)
# Returns wlutil.test.testResult
def testWorkload(cfgName, cfgs, verbose=False, spike=False, cmp_only=None):
    """Test the workload specified by cfgName.
    cfgName: unique name of the workload in the cfgs
    cfgs: initialized configuration (contains all possible workloads)
    verbose: If true, the workload outputs will be displayed live, otherwise
        they will be silently logged.
    spike: Test using spike instead of the default qemu
    cmp_only (path): Do not run the workload. Instead, simply compare the
        golden output against the path in cmp_only. For example, cmp_only could
        point to the output of a FireSim run. 

    Returns (wlutil.test.testResult, output directory)
    """

    log = logging.getLogger()

    cfg = cfgs[cfgName]
    if 'testing' not in cfg:
        log.info("Test " + cfgName + " skipping: No 'testing' field in config")
        return testResult.skip, None

    testCfg = cfg['testing']
        
    if 'buildTimeout' not in testCfg:
        testCfg['buildTimeout'] = defBuildTimeout
    if 'runTimeout' not in testCfg:
        testCfg['runTimeout'] = defRunTimeout

    refPath = cfg['workdir'] / testCfg['refDir']
    if cmp_only is None:
        testPath = getOpt('res-dir') / getOpt('run-name')
    else:
        testPath = cmp_only

    try:
        if cmp_only is None:
            # Build workload
            log.info("Building test workload")
            runTimeout(buildWorkload, testCfg['buildTimeout'])(cfgName, cfgs)

            # Run every job (or just the workload itself if no jobs)
            if 'jobs' in cfg:
                for jName in cfg['jobs'].keys():
                    log.info("Running job " + jName)
                    runTimeout(launchWorkload, testCfg['runTimeout'])(cfg, job=jName, spike=spike, interactive=verbose)
            else:
                log.info("Running workload")
                runTimeout(launchWorkload, testCfg['runTimeout'])(cfg, spike=spike, interactive=verbose)

        log.info("Testing outputs")    
        if 'strip' in testCfg and testCfg['strip']:
            stripUartlog(cfg, testPath)

        diff = cmpOutput(testPath, refPath)
        if diff is not None:
            suitePass = False
            log.info("Test " + cfgName + " failure: output does not match reference")
            log.info(textwrap.indent(diff, '\t'))
            return testResult.failure, testPath

    except TimeoutError as e:
        suitePass = False
        if e.args[0] == "buildWorkload":
            log.info("Test " + cfgName + " failure: timeout while building")
        elif e.args[0] == "launchWorkload":
            log.info("Test " + cfgName + " failure: timeout while running")
        else:
            log.error("Internal tester error: timeout from unrecognized function: " + e.args[0])
        
        return testResult.failure, testPath

    except ChildProcessError as e:
        suitePass = False
        if e.args[0] == "buildWorkload":
            log.info("Test " + cfgName + " failure: Exception while building")
        elif e.args[0] == "launchWorkload":
            log.info("Test " + cfgName + " failure: Exception while running")
        else:
            log.error("Internal tester error: exception in unknown function: " + e.args[0])
        
        return testResult.failure, testPath

    except Exception as e:
        suitePass = False
        log.info("Test " + cfgName + " failure: Exception encountered")
        traceback.print_exc()
        return testResult.failure, testPath

    return testResult.success, testPath

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Check the outupt of a workload against a reference output. The reference directory should match the layout of test directory including any jobs, uartlogs, or file outputs. Reference uartlogs can be a subset of the full output (this will check only that the reference uartlog content exists somewhere in the test uartlog).")
    parser.add_argument("testDir", help="Run output directory to test.")
    parser.add_argument("refDir", help="Reference output directory.")

    args = parser.parse_args()
    res = cmpOutput(args.testDir, args.refDir)
    if res is not None:
        print("Failure:")
        print(res)
        sys.exit(os.EX_DATAERR)
    else:
        print("Success")
        sys.exit(os.EX_OK)


