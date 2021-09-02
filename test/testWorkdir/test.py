#!/usr/bin/env python3

import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent
testCfg = testSrc / "testWorkdir.yaml"

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

# Safety first kids: Always clean before you test
print("cleaning testWorkload test")
cleanCmd = [str(managerPath), "--workdir", "../", "clean", "testWorkdir.yaml"]
print(" ".join(cleanCmd))
sp.run(cleanCmd, cwd=testSrc, check=True)

print("Building workload with non-local workload bases")
testCmd = [str(managerPath), "--workdir", "../", "test", "testWorkdir.yaml"]
print(" ".join(testCmd))
sp.run(testCmd, cwd=testSrc, check=True)

print("testWorkdir test Success", file=sys.stderr)
sys.exit()
