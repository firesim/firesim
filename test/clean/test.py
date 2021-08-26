#!/usr/bin/env python3
"""Call as ./test.py PATH/TO/MARSHAL"""

import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent
testCfg = testSrc.parent / "clean.yaml"

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

# Safety first kids: Always clean before you test
print("Cleaning the test the first time:")
if sp.call(str(managerPath) + " clean " + str(testCfg), shell=True) != 0:
    print("Clean Test Failure: the first clean command failed", file=sys.stderr)
    sys.exit(1)

# First run should succeed
print("The first run of this test should succeed:")
if sp.call(str(managerPath) + " test " + str(testCfg), shell=True) != 0:
    print("Clean Test Failure: first run of test failed", file=sys.stderr)
    sys.exit(1)

# Second run should fail
print("The second run of this test should fail:")
if sp.call(str(managerPath) + " test " + str(testCfg), shell=True) == 0:
    print("Clean Test Failure: the second run of the clean workload should not succeed, but it did!", file=sys.stderr)
    sys.exit(1)

# If we clean it, it should work again
print("Buf if we clean it, it should pass again:")
if sp.call(str(managerPath) + " clean " + str(testCfg), shell=True) != 0:
    print("Clean Test Failure: the second clean command failed", file=sys.stderr)
    sys.exit(1)

if sp.check_call(str(managerPath) + " test " + str(testCfg), shell=True) != 0:
    print("Clean Test Failure: the test did not succeed after cleaning", file=sys.stderr)
    sys.exit(1)

print("Clean Test Success", file=sys.stderr)
sys.exit()
