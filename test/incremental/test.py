#!/usr/bin/env python3
"""Perform a test of incremental builds in FireMarshal. This should be called
on the host and will run a series of marshal commands.

Usage: ./test.py PATH/TO/MARSHAL
"""

import subprocess as sp
import sys
import pathlib as pth

usage = """Usage: ./test.py PATH/TO/MARSHAL"""

# Should be the directory containing the incremental test
testSrc = pth.Path(__file__).parent
testCfg = testSrc.parent / "incremental.yaml"

# Should be the directory containing marshal
if len(sys.argv) != 2:
    print(usage)
    sys.exit(1)

managerPath = pth.Path(sys.argv[1])
if not managerPath.exists:
    print("Provided marshal command does not exist: ", managerPath)
    sys.exit(1)

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
