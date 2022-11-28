#!/usr/bin/env python3
import pathlib
import sys
import argparse
import time
import logging
import subprocess as sp
import os

script_dir = os.path.dirname(os.path.realpath(__file__))

sys.path.append(script_dir + "/..")
import wlutil  # NOQA

rootDir = pathlib.Path(__file__).parent.resolve()
logDir = rootDir / "testLogs"
testDir = (rootDir / '../test').resolve()
marshalBin = (rootDir / "../marshal").resolve()

categories = ['baremetal', 'qemu', 'spike', 'smoke', 'special']

# Arguments to (marshal, marshal CMD) per category
categoryArgs = {
        'baremetal': (['-v'], ["--spike"]),
        'qemu': (['-v'], []),
        'smoke': (['-v'], []),
        'spike': (['-v', '--no-disk'], ['--spike']),
        'special': (['-v'], [])
}

# lists of test names to run for each category, each name
# should correspond to a test in FireMarshal/tests. E.G. "command" means
# "FireMarshal/test/command.yaml".
categoryTests = {
        # Run on spike. These tests depend only on an installed toolchain, you
        # don't need to initialize Marshal's submodules to run this category
        'baremetal': [
            'bare',
            'dummy-bare',
            'spike',
            'spike-jobs',
            'spike-args',
            'rocc'
        ],

        # These is the most complete 'general' tests and is the way most people
        # will use Marshal
        "qemu": [
            'bbl',
            'bbl-src',
            'bbl-args',
            'opensbi-src',
            'opensbi-args',
            'command',
            'driversJob',
            'drivers',
            'fed-run',
            'flist',
            'generateFiles',
            'guest-init',
            'host-init',
            'jobs',
            'kfrag',
            'linux-src',
            'makefile',
            'outputs',
            'overlay',
            'post-bin-jobs',
            'post-bin',
            'post_run_hook',
            'qemu-args',
            'qemu',
            'run',
            'simArgs',
            'noDrivers'
        ],

        # This tests both no-disk and spike. In theory, most (maybe all?) tests
        # in "qemu" could run nodisk on spike, but it wouldn't really test
        # anything new. We just include a few at-risk tests here to shave a few
        # hours off the full test. Smoke runs also use spike.
        "spike": [
           'command',
           'flist',
           'host-init',
           'jobs',
           'linux-src',
           'overlay',
           'post_run_hook',
           'simArgs',
           'bbl'
           ],

        # A hopefully minimal and fast(ish) set of tests to make sure nothing
        # obvious is broken
        "smoke": [
            'fed-smoke0',
            'smoke0',
            'smoke1',
            'smoke2',
        ],

        # These tests aren't run directly. Instead they include a testing
        # script that is run.
        "special": [
                'clean',
                'incremental',
                'inherit',
                'sameWorkdir',
                'fsSize',
                'makefile',
                'testWorkdir',
                'workload-dirs',
                'jobs',
                'undefinedOpt',
                'modifyDistro'
        ]
}


def runTests(testNames, categoryName, marshalArgs=[], cmdArgs=[]):
    """Run the tests named in testNames. Logging will use categoryName to
    identify this set of tests. marshalArgs and cmdArgs are the arguments to
    pass to 'marshal' and 'marshal test', respectively."""
    log = logging.getLogger()

    # Tuples of (testName, exception) for each failed test
    failures = []

    for tName in testNames:
        log.log(logging.INFO, "[{}] {}:".format(categoryName, tName))
        tPath = testDir / (tName + ".yaml")

        try:
            # These log at level DEBUG (go to log file but not stdout)
            wlutil.run([marshalBin] + marshalArgs + ['clean', tPath], check=True)
            wlutil.run([marshalBin] + marshalArgs + ['test'] + cmdArgs + [tPath], check=True)
        except sp.CalledProcessError as e:
            log.log(logging.INFO, "FAIL")
            failures.append(("[{}]: {}".format(categoryName, tName), e))
            continue

        log.log(logging.INFO, "PASS")

    return failures


def runSpecial(testNames, categoryName):
    """Run the tests named in testNamed assuming they are special tests. Each
    name should be a directory under firemarshal/test/ and should have a
    test.py script that will be run and indicates pass/fail via return code.
    The tests will be called as such: ./test.py pathToMarshalBin"""

    log = logging.getLogger()

    # Tuples of (testName, exception) for each failed test
    failures = []

    for tName in testNames:
        log.log(logging.INFO, "[{}] {}:".format(categoryName, tName))
        tPath = testDir / tName

        try:
            wlutil.run(["python3", tPath / "test.py", marshalBin], check=True, shell=True)
        except sp.CalledProcessError as e:
            log.log(logging.INFO, "FAIL")
            failures.append(("[{}]: {}".format(categoryName, tName), e))
            continue

        log.log(logging.INFO, "PASS")

    return failures


if __name__ == "__main__":
    logDir.mkdir(exist_ok=True)

    timeline = time.strftime("%Y-%m-%d--%H-%M-%S", time.gmtime())
    logPath = logDir / (timeline + "-FullTest.log")
    wlutil.initLogging(False, logPath=logPath)
    log = logging.getLogger()

    log.log(logging.INFO, "Logging live to: " + str(logPath))

    parser = argparse.ArgumentParser(description="Run end-to-end FireMarshal tests (mostly in FireMarshal/test)")

    parser.add_argument("-c", "--categories", nargs="+", default=list(categories),
                        help="Specify which categorie(s) of test to run. By default, all tests will be run")

    # TODO: add a 'from-failures' option to only run tests that failed a previous run

    args = parser.parse_args()

    allFailures = []
    for category in args.categories:
        if category != 'special':
            allFailures += runTests(categoryTests[category], category,
                                    marshalArgs=categoryArgs[category][0],
                                    cmdArgs=categoryArgs[category][1])
        else:
            allFailures += runSpecial(categoryTests["special"], "SPECIAL")

    log.info("Test Summary:")
    if len(allFailures) > 0:
        log.info("Some tests failed:")
        for fail in allFailures:
            log.info(fail[0])
        sys.exit(1)
    else:
        log.info("All PASS")
        sys.exit(0)
