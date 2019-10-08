#!/usr/bin/env python3

import subprocess as sp
import sys
import os
import pathlib as pth
import re

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
testCfg = testSrc.parent / "inherit-child.json"

print("testSrc:",testSrc)
print("testCfg:",testCfg)
# Should be the directory containing marshal
managerPath = pth.Path(os.getcwd()) / "marshal"
if not managerPath.exists():
    managerPath = testSrc / "../../marshal"
    if not managerPath.exists():
        print("Can't find marshal, this script should be called either from firemarshal root or firesim-software/test/inherit/", file=sys.stderr)
        sys.exit(1)

# Safety first kids: Always clean before you test
print("Cleaning the test:")
if sp.call(str(managerPath) + " clean " + str(testCfg), shell=True) != 0:
    print("Test Failure: clean command failed", file=sys.stderr)
    sys.exit(1)

print("Cleaning host-init")
(testSrc / 'runOutput').unlink()

print("Testing child workload:")
if sp.call(str(managerPath) + " test " + str(testCfg), shell=True) != 0:
    print("Clean Test Failure", file=sys.stderr)
    sys.exit(1)

print("Inherit Test Success", file=sys.stderr)
sys.exit()
