#!/usr/bin/env python3
"""Call as ./test.py PATH/TO/MARSHAL"""

import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
testCfg = testSrc.parent / "undefinedOpt.yaml"

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

print("Cleaning the test the first time:")
proc = sp.run([managerPath, "clean", str(testCfg)])
if proc.returncode != 0:
    print("Failure when cleaning test", file=sys.stderr)
    sys.exit(1)

print("Running with --werr, should fail:")
proc = sp.run([managerPath, "--werr", "test", str(testCfg)])
if proc.returncode == 0:
    print("Test succeeded when it should have failed", file=sys.stderr)
    sys.exit(1)

print("Running without --werr should pass with a warning:")
proc = sp.run([managerPath, "test", str(testCfg)], stdout=sp.PIPE)
if proc.returncode != 0:
    print("Test failed", file=sys.stderr)
    sys.exit(1)
elif "WARNING" not in proc.stdout.decode("utf-8"):
    print("No warning present!")
    sys.exit(1)

print("Test Success", file=sys.stderr)
sys.exit()
