#!/usr/bin/env python3
"""Call as ./test.py PATH/TO/MARSHAL"""

import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
testCfg = testSrc.parent / "jobs.yaml"

if len(sys.argv) > 1:
    managerPath = pth.Path(sys.argv[1])
else:
    # Should be the directory containing marshal
    managerPath = pth.Path(os.getcwd()) / "marshal"
    if not managerPath.exists:
        managerPath = pth.Path(os.getcwd()) / "../../marshal"
        if not managerPath.exists:
            print("Can't find marshal, this script should be called either from firesim-software/ or firesim-software/test/incremental/", file=sys.stderr)
            sys.exit(1)

outs = {
        "root": "root : run",
        "j0": "j0 : run",
        "j1": "j1 : run"
        }


outsSubDir = {
        "root": "jobs",
        "j0": "jobs-j0",
        "j1": "jobs-j1"
        }


# Parse stdout of marshal command to find runOutput dir
def findOutDir(stdout):
    stdout = stdout.decode("utf-8")
    for line in stdout.split("\n"):
        if "Workload outputs available at" in line:
            pathStr = line.split(":")[1].strip()
            return pth.Path(pathStr)


# Check for required outputs (wamt) in uartlog files inside outDir
def checkOuts(want, outDir):
    for name, output in outs.items():
        rPath = outDir / outsSubDir[name] / "uartlog"

        if not rPath.is_file():
            if name in want:
                print("Result file not found at :" + str(rPath), file=sys.stderr)
                return False
            continue

        with open(str(rPath), 'r') as rFile:
            stdout = rFile.read()
            if name in want:
                if output not in stdout:
                    print("Expected output '" + output + "' not in stdout", file=sys.stderr)
                    return False
            elif output in stdout:
                print("Unexpected output '" + output + "' in stdout", file=sys.stderr)
                return False

    return True


# Safety first kids: Always clean before you test
print("Cleaning the test the first time:")
if sp.call(str(managerPath) + " clean " + str(testCfg), shell=True) != 0:
    print("Failure: clean command failed", file=sys.stderr)
    sys.exit(1)

print("Build Workload")
if sp.call(str(managerPath) + " build " + str(testCfg), shell=True, stdout=sp.PIPE) != 0:
    print("Failure: couldn't build workload", file=sys.stderr)
    sys.exit(1)

print("Run Root Only")
proc = sp.run(str(managerPath) + " launch " + str(testCfg), shell=True, stdout=sp.PIPE)
if proc.returncode != 0:
    print("Failure: marshal returned non-zero exit code", file=sys.stderr)
    sys.exit(1)
elif not checkOuts(['root'], findOutDir(proc.stdout)):
    print("Failure: contained incorrect outputs", file=sys.stderr)
    sys.exit(1)

print("Run j0 Only")
proc = sp.run(str(managerPath) + " launch -j j0 " + str(testCfg), shell=True, stdout=sp.PIPE)
if proc.returncode != 0:
    print("Failure: marshal returned non-zero exit code", file=sys.stderr)
    sys.exit(1)
elif not checkOuts(['j0'], findOutDir(proc.stdout)):
    print("Failure: contained incorrect outputs", file=sys.stderr)
    sys.exit(1)

print("Run j1 Only")
proc = sp.run(str(managerPath) + " launch -j j1 " + str(testCfg), shell=True, stdout=sp.PIPE)
if proc.returncode != 0:
    print("Failure: marshal returned non-zero exit code", file=sys.stderr)
    sys.exit(1)
elif not checkOuts(['j1'], findOutDir(proc.stdout)):
    print("Failure: contained incorrect outputs", file=sys.stderr)
    sys.exit(1)

print("Run Both Jobs Explicitly")
proc = sp.run(str(managerPath) + " launch -j j0 -j j1 " + str(testCfg), shell=True, stdout=sp.PIPE)
if proc.returncode != 0:
    print("Failure: marshal returned non-zero exit code", file=sys.stderr)
    sys.exit(1)
elif not checkOuts(['j1', 'j0'], findOutDir(proc.stdout)):
    print("Failure: contained incorrect outputs", file=sys.stderr)
    sys.exit(1)

print("Run All Jobs")
proc = sp.run(str(managerPath) + " launch -a " + str(testCfg), shell=True, stdout=sp.PIPE)
if proc.returncode != 0:
    print("Failure: marshal returned non-zero exit code", file=sys.stderr)
    sys.exit(1)
elif not checkOuts(['j1', 'j0'], findOutDir(proc.stdout)):
    print("Failure: contained incorrect outputs", file=sys.stderr)
    sys.exit(1)

print("Test Success", file=sys.stderr)
sys.exit()
