#!/usr/bin/env python3
import sys
import argparse
import pathlib as pl
import difflib
import pprint
import os

# Compares two runOutput directories. Returns None if they match or a message
# describing the difference if they don't.
#   - Directory structures are compared directly (same folders in the same
#     places). Files/Directories in testDir that don't exist in refDir are
#     ignored (refDir is a subset of testDir).
#   - Regular files are compared using standard diff (reports line # of
#     difference, must match exactly)
#   - Files named "uartlog" in the reference output need only match a subset of
#     the test output (the entire reference uartlog contents must exist somewhere
#     in the test output).
def cmpOutput(testDir, refDir):
    testDir = pl.Path(testDir)
    refDir = pl.Path(refDir)
    for rPath in refDir.glob("**/*"):
        # tPath = testDir / pl.Path(*rPath.parts[1:])
        tPath = testDir / rPath.relative_to(refDir)
        if not tPath.exists():
            return "Missing file or directory: " + str(tPath)

        if rPath.is_file():
            # Regular file, should match exactly
            with open(str(rPath), 'r') as rFile:
                with open(str(tPath), 'r') as tFile:
                    if rPath.name == "uartlog":
                        rLines = rFile.readlines()
                        tLines = tFile.readlines()
                        matcher = difflib.SequenceMatcher(None, rLines, tLines)
                        m = matcher.find_longest_match(0, len(rLines), 0, len(tLines))
                        if m.size != len(rLines):
                            if m.size == 0:
                                return str(rPath) + " and " + str(tPath) + " do not match"
                            else:
                                return str(tPath) + " matches only at " + \
                                       str(rPath) + ":" + str(m.a) + "," + str(m.a + m.size) + "\n" + \
                                       "".join(rLines[m.a : m.a + m.size])
                    else:
                        # I'm not 100% sure what will happen with a binary file
                        diffString = "".join(difflib.unified_diff(rFile.readlines(),
                                tFile.readlines(), fromfile=rPath, tofile=tPath))
                        if diffString is not "":
                            return diffString

    return None


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Check the outupt of a workload against a reference output. The reference directory should match the layout of test directory including any jobs, uartlogs, or file outputs. Reference uartlogs can be a subset of the full output (this will check only that the reference uartlog content exists somewhere in the test uartlog).")
    parser.add_argument("testDir", help="Run output directory to test.")
    parser.add_argument("refDir", help="Reference output directory.")

    args = parser.parse_args()
    res = cmpOutput(args.testDir, args.refDir)
    if res is not None:
        print("Failure:")
        print(res)
        sys.exit(os.EX_DATAERR)
    else:
        print("Success")
        sys.exit(os.EX_OK)
