import sys
import os
import subprocess as sp
import pathlib as pth

testSrc = pth.Path(__file__).parent.resolve()
testCfg = testSrc.parent / "fsSize.yaml"
marshalBin = pth.Path(sys.argv[1])


# def runTest(tDir):
fail = False
try:
    print("Generating input files")
    try:
        sp.run(["dd", "if=/dev/zero", "of=" + str(testSrc / "huge0.dat"), "bs=1M", "count=1024"], check=True)
        sp.run(["dd", "if=/dev/zero", "of=" + str(testSrc / "huge1.dat"), "bs=1M", "count=1024"], check=True)
    except sp.CalledProcessError as e:
        print("Failed to generate input files: ", e)
        raise

    print("Testing Workload")
    try:
        sp.run([marshalBin, "test", testCfg], check=True)
    except sp.CalledProcessError as e:
        print("Failed build and run workload: ", e)
        raise

    try:
        sp.run([marshalBin, "clean", testCfg], check=True)
    except sp.CalledProcessError as e:
        print("Failed cleanup workload. You should manually verify that the image files were deleted (they are very large)")
        print(e)
        raise

except Exception as e:
    print("Exception while running: ", e)
    fail = True

finally:
    try:
        os.remove(testSrc / "huge0.dat")
        os.remove(testSrc / "huge1.dat")
    except FileNotFoundError:
        pass

if fail:
    sys.exit(1)
else:
    sys.exit(0)
