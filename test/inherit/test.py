#!/usr/bin/env python3

import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
copyCfg = testSrc.parent / "inherit-copy.yaml"
ownBinCfg = testSrc.parent / "inherit-childOwnBin.yaml"
parentCfg = testSrc.parent / "inherit-parent.yaml"

if len(sys.argv) > 1:
    managerPath = pth.Path(sys.argv[1])
else:
    # Should be the directory containing marshal
    managerPath = pth.Path(os.getcwd()) / "marshal"
    if not managerPath.exists():
        managerPath = testSrc / "../../marshal"
        if not managerPath.exists():
            print("Can't find marshal, this script should be called either from firemarshal root or firesim-software/test/inherit/", file=sys.stderr)
            sys.exit(1)

binDir = managerPath.parent / 'images'

# Safety first kids: Always clean before you test
print("Cleaning the test:")
try:
    sp.run(str(managerPath) + " clean " + str(ownBinCfg), shell=True, check=True)
    sp.run(str(managerPath) + " clean " + str(copyCfg), shell=True, check=True)
    sp.run(str(managerPath) + " clean " + str(parentCfg), shell=True, check=True)
except Exception as e:
    print("Test Failure: clean command failed: ", str(e), file=sys.stderr)
    sys.exit(1)

print("Cleaning host-init")
try:
    (testSrc / 'runOutput').unlink()
except FileNotFoundError:
    pass

print("Testing child (must rebuild bin) workload:")
if sp.call(str(managerPath) + " test " + str(ownBinCfg), shell=True) != 0:
    print("Inherit Test Failure: failed to build inherit-childOwnBin", file=sys.stderr)
    sys.exit(1)

# Parent binary should not have been built since the child had to build its own
if (binDir / "inherit-parent-bin").exists():
    print("Parent was built, Marshal didn't mark the child to build its own binary")
    sys.exit(1)

print("Testing child (can inherit bin) workload:")
if sp.call(str(managerPath) + " test " + str(copyCfg), shell=True) != 0:
    print("Inherit Test Failure: failed to build inherit-copy", file=sys.stderr)
    sys.exit(1)

# Parent binary should have been built since the child can use it
if not (binDir / "inherit-parent-bin").exists():
    print("parent bin: ", str(binDir / "inherit-parent-bin"))
    print("Parent not built, Marshal didn't mark the child to use its parent's binary")
    sys.exit(1)

print("Inherit Test Success", file=sys.stderr)
sys.exit()
