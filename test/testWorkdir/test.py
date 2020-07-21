#!/usr/bin/env python3

import subprocess as sp
import sys
import os
import pathlib as pth
import re

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent
testCfg = testSrc.parent / "testWorkdir.json"

if len(sys.argv) > 1:
    managerPath = pth.Path(sys.argv[1])
else:
    # Should be the directory containing marshal
    managerPath = pth.Path(os.getcwd()) / "marshal"
    if not managerPath.exists():
        managerPath = pth.Path(os.getcwd()) / "../../marshal"
        if not managerPath.exists:
            print("Can't find marshal, this script should be called either from FireMarshal/ or FireMarshal/test/testWorkload/", file=sys.stderr)
            sys.exit(1)

print(str(managerPath))

# Safety first kids: Always clean before you test
print("cleaning testWorkload test")
cleanCmd = [str(managerPath), "--workdir", "../", "clean", str(testCfg)]
print(" ".join(cleanCmd))
if sp.call(cleanCmd) != 0:
    print("Clean Test Failure: the first clean command failed", file=sys.stderr)
    sys.exit(1)

print("Building workload with non-local workload bases")
testCmd = [str(managerPath), "--workdir", "../", "test", str(testCfg)]
print(" ".join(testCmd))
if sp.call(testCmd) != 0:
    print("Clean Test Failure: first run of test failed", file=sys.stderr)
    sys.exit(1)

print("testWorkdir test Success", file=sys.stderr)
sys.exit()
