import subprocess as sp
import sys
import pathlib as pth

testSrc = pth.Path(__file__).parent.resolve()
managerPath = pth.Path(sys.argv[1])

sp.run([managerPath, "test", "command.yaml"], check=True, cwd=testSrc)
