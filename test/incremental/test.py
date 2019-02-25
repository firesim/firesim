#!/usr/bin/env python3

import subprocess as sp
import sys
import os
import pathlib as pth
import re

# Should be the directory containing the incremental test
testSrc = pth.Path(__file__).parent
testCfg = testSrc.parent / "incremental.json"

# Should be the directory containing marshal
managerPath = pth.Path(os.getcwd()) / "marshal"
if not managerPath.exists:
    managerPath = pth.Path(os.getcwd()) / "../../marshal"
    if not managerPath.exists:
        print("Can't find marshal, this script should be called either from firesim-software/ or firesim-software/test/incremental/", file=sys.stderr)

# Reset the test, just in case it was left in a weird state
sp.check_call(str(managerPath) + " clean " + str(testCfg), shell=True)
with (testSrc / "testFile").open('w') as f:
    f.write("Global : file")

# Build and run the workload: the image should have two files after this:
#    /root/runOutupt = "Global : command"
#    /root/testFile  = "Global : file"
sp.check_call(str(managerPath) + " build " + str(testCfg), shell=True)
sp.check_call(str(managerPath) + " launch " + str(testCfg), shell=True)

# Modify the source file to force an incremental update
with (testSrc / "testFile").open('w') as f:
    f.write("Global : incrementally")

try:
    # The refOutput is setup to match the run now. The testFile should be updated
    # to "Global : incrementally", but the runOutput should not have been updated
    # and have two "Global : command" entries (once for the first run, and once for
    # this test)
    sp.check_call(str(managerPath) + " test " + str(testCfg), shell=True)
finally:
    # Put everything back where we found it
    with (testSrc / "testFile").open('w') as f:
        f.write("Global : file")
    
    sp.check_call(str(managerPath) + " clean " + str(testCfg), shell=True)

print("Incremental test success", file=sys.stderr)
