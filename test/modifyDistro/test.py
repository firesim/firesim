#!/usr/bin/env python3
"""Perform a test of various distro customization features. This should be
called on the host and will run a series of marshal commands.

Usage: ./test.py PATH/TO/MARSHAL
"""

import subprocess as sp
import sys
import os
import pathlib as pth

usage = """Usage: ./test.py PATH/TO/MARSHAL"""

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
testCfg = testSrc.parent / "modifyDistro.yaml"

# Should be the directory containing marshal
if len(sys.argv) != 2:
    print(usage)
    sys.exit(1)

managerPath = pth.Path(sys.argv[1])
if not managerPath.exists:
    print("Provided marshal command does not exist: ", managerPath)
    sys.exit(1)

# Reset the test, just in case it was left in a weird state
sp.run([str(managerPath), "clean", str(testCfg)], check=True)

testEnv = os.environ.copy()
testEnv['MODIFYDISTRO_TEST_HOSTNAME_BASE'] = "fromenv"
proc = sp.run([str(managerPath), "test", str(testCfg)], env=testEnv)
if proc.returncode != 0:
    print("ModifyDistro Test Failure", file=sys.stderr)
    sys.exit(1)

print("ModifyDistro test success", file=sys.stderr)
