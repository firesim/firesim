import subprocess as sp
import sys
import os
import pathlib as pth

# Should be the directory containing the test
testSrc = pth.Path(__file__).parent.resolve()
os.chdir(testSrc)

managerPath = pth.Path(sys.argv[1])

if sp.call(str(managerPath) + " test sameDir.yaml", shell=True) != 0:
    print("Clean Test Failure", file=sys.stderr)
    sys.exit(1)
else:
    print("Success")
    sys.exit(0)
