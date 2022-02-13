import pathlib as pl
import difflib
from contextlib import contextmanager
import logging
import traceback
import textwrap
from enum import Enum
import signal

from . import wlutil
from . import build as wlbuild
from . import launch as wllaunch

testResult = Enum('testResult', ['success', 'failure', 'skip'])

# Default timeouts (in seconds)
defBuildTimeout = 2400
defRunTimeout = 2400


class TestFailure(Exception):
    def __init__(self, msg):
        self.msg = msg

    def __str__(self):
        return self.msg


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
def cmpOutput(config, testDir, refDir, strip=False):
    testDir = pl.Path(testDir)
    refDir = pl.Path(refDir)
    if not refDir.exists():
        return "reference directory: " + str(refDir) + " does not exist"

    for rPath in refDir.glob("**/*"):
        # tPath = testDir / pl.Path(*rPath.parts[1:])
        tPath = testDir / rPath.relative_to(refDir)
        if not tPath.exists():
            return "Missing file or directory: " + str(tPath)

        if rPath.is_file():
            # Regular file, should match exactly
            with open(str(rPath), 'r') as rFile:
                with open(str(tPath), 'r', newline="\n") as tFile:
                    if rPath.name == "uartlog":
                        rLines = rFile.readlines()
                        tLines = tFile.readlines()

                        # Some configurations spit out a bunch of spurious \r\n
                        # (^M in vim) characters. This strips them so that
                        # users can type reference outputs using normal
                        # newlines.
                        tLines = [line.replace("\r", "") for line in tLines]
                        if strip:
                            tLines = config['builder'].stripUart(tLines)

                        matcher = difflib.SequenceMatcher(None, rLines, tLines)
                        m = matcher.find_longest_match(0, len(rLines), 0, len(tLines))
                        if m.size != len(rLines):
                            if m.size == 0:
                                return str(rPath) + " and " + str(tPath) + " do not match"
                            else:
                                return str(tPath) + " matches only at " + \
                                       str(rPath) + ":" + str(m.a) + "," + str(m.a + m.size) + "\n" + \
                                       "".join(rLines[m.a: m.a + m.size])
                    else:
                        # I'm not 100% sure what will happen with a binary file
                        diffString = "".join(difflib.unified_diff(rFile.readlines(),
                                             tFile.readlines(), fromfile=str(rPath), tofile=str(tPath)))
                        if diffString != "":
                            return diffString

    return None


@contextmanager
def timeout(seconds, label):
    """Raises TimeoutError if the block takes longer than 'seconds' (an integer)"""
    def timeoutHandler(signum, fname):
        raise TimeoutError(label)

    oldSignal = signal.signal(signal.SIGALRM, timeoutHandler)
    signal.alarm(seconds)
    try:
        yield
    finally:
        signal.signal(signal.SIGALRM, oldSignal)
        signal.alarm(0)


# Build and run a workload and compare results against the testing spec
# ('testing' field in config)
# Returns wlutil.test.testResult
def testWorkload(cfgName, cfgs, verbose=False, spike=False, cmp_only=None):
    """Test the workload specified by cfgName.
    cfgName: unique name of the workload in the cfgs
    cfgs: initialized configuration (contains all possible workloads)
    verbose: If true, the workload outputs will be displayed live, otherwise
        they will be silently logged.
    spike: Test using spike instead of the default qemu
    cmp_only (path): Do not run the workload. Instead, simply compare the
        golden output against the path in cmp_only. For example, cmp_only could
        point to the output of a FireSim run.

    Returns (wlutil.test.testResult, output directory)
    """

    log = logging.getLogger()

    cfg = cfgs[cfgName]
    if 'testing' not in cfg:
        log.info("Test " + cfgName + " skipping: No 'testing' field in config")
        return testResult.skip, None

    testCfg = cfg['testing']

    if 'buildTimeout' not in testCfg:
        testCfg['buildTimeout'] = defBuildTimeout
    if 'runTimeout' not in testCfg:
        testCfg['runTimeout'] = defRunTimeout

    refPath = cfg['workdir'] / testCfg['refDir']
    if cmp_only is None:
        testPath = wlutil.getOpt('res-dir') / wlutil.getOpt('run-name')
    else:
        testPath = cmp_only

    try:
        if cmp_only is None:
            # Build workload
            log.info("Building test workload")
            with timeout(testCfg['buildTimeout'], 'build'):
                res = wlbuild.buildWorkload(cfgName, cfgs)

            if res != 0:
                raise TestFailure("Failure when building workload " + cfgName)

            # Run every job (or just the workload itself if no jobs)
            if 'jobs' in cfg:
                for jName in cfg['jobs'].keys():
                    log.info("Running job " + jName)
                    with timeout(testCfg['runTimeout'], 'launch job' + jName):
                        wllaunch.launchWorkload(cfg, jobs=[jName], spike=spike, silent=not verbose)
            else:
                log.info("Running workload")
                with timeout(testCfg['runTimeout'], 'launch'):
                    wllaunch.launchWorkload(cfg, spike=spike, silent=not verbose)

        log.info("Testing outputs")
        strip = False
        if 'strip' in testCfg and testCfg['strip']:
            strip = True

        diff = cmpOutput(cfg, testPath, refPath, strip=strip)
        if diff is not None:
            log.info("Test " + cfgName + " failure: output does not match reference")
            log.info(textwrap.indent(diff, '\t'))
            return testResult.failure, testPath

    except TimeoutError as e:
        if e.args[0] == "buildWorkload":
            log.info("Test " + cfgName + " failure: timeout while building")
        elif e.args[0] == "launchWorkload":
            log.info("Test " + cfgName + " failure: timeout while running")
        else:
            log.error("Internal tester error: timeout from unrecognized function: " + e.args[0])

        return testResult.failure, testPath

    except TestFailure as e:
        log.info(e.msg)

        return testResult.failure, testPath

    except Exception:
        log.info("Test " + cfgName + " failure: Exception encountered")
        traceback.print_exc()
        return testResult.failure, testPath

    return testResult.success, testPath
