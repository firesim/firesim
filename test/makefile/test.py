import pathlib as pth
import subprocess as sp
import os
import sys

testSrc = pth.Path(__file__).parent.resolve()
managerPath = pth.Path(sys.argv[1])

os.environ['MARSHALBIN'] = str(managerPath)
sp.run("make", shell=True, cwd=testSrc)
