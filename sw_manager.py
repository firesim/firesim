#!/usr/bin/env python3
import sys
import argparse
import os
import logging
import wlutil
import contextlib

if 'RISCV' not in os.environ:
    sys.exit("Please source firesim/sourceme-manager-f1.sh first\n")

# Delete a file but don't throw an exception if it doesn't exist
def deleteSafe(pth):
    with contextlib.suppress(FileNotFoundError):
        os.remove(pth)

def main():
    parser = argparse.ArgumentParser(
        description="Build and run (in spike or qemu) boot code and disk images for firesim")
    parser.add_argument('--workdir', help='Use a custom workload directory (defaults to the same directory as the first config file)')
    parser.add_argument('-v', '--verbose',
                        help='Print all output of subcommands to stdout as well as the logs', action='store_true')
    parser.add_argument('-i', '--initramfs', action='store_true', help="Use the initramfs version of this workload")
    subparsers = parser.add_subparsers(title='Commands', dest='command')

    # Build command
    build_parser = subparsers.add_parser(
        'build', help='Build an image from the given configuration.')
    build_parser.add_argument('config_files', metavar="config", nargs='+', help="Configuration file(s) to use.")
    build_parser.add_argument('-B', '--binOnly', action='store_true', help="Only build the binary")
    build_parser.add_argument('-I', '--imgOnly', action='store_true', help="Only build the image (may require an image if you have guest-init scripts)")

    # Launch command
    launch_parser = subparsers.add_parser(
        'launch', help='Launch an image on a software simulator (defaults to qemu)')
    launch_parser.add_argument('-s', '--spike', action='store_true',
            help="Use the spike isa simulator instead of qemu")
    launch_parser.add_argument('-j', '--job', nargs='?', default='all',
            help="Launch the specified job. Defaults to running the base image.")
    # the type= option here allows us to only accept one argument but store it
    # in a list so it matches the "build" behavior
    launch_parser.add_argument('config_files', metavar='config', nargs='?', type=(lambda c: [ c ]), help="Configuration file to use.")

    # Test command
    test_parser = subparsers.add_parser(
            'test', help="Test each workload.")
    test_parser.add_argument('config_files', metavar="config", nargs='+', help="Configuration file(s) to use.")
    test_parser.add_argument('-s', '--spike', action='store_true',
            help="Use the spike isa simulator instead of qemu")

    # Clean Command
    clean_parser = subparsers.add_parser(
            'clean', help="Removes build outputs of the provided config (img and bin). Does not affect logs or runOutputs.")
    clean_parser.add_argument('config_files', metavar="config", nargs='+', help="Configuration file(s) to use.")

    args = parser.parse_args()
    
    # Load all the configs from the workload directory
    args.config_files = [ os.path.abspath(f) for f in args.config_files ] 
    if args.workdir is None:
        args.workdir = os.path.dirname(args.config_files[0])
    cfgs = wlutil.ConfigManager([args.workdir])

    if args.command == 'test':
        suitePass = True

    for cfgPath in args.config_files:
        # Each config gets it's own logging output and results directory
        wlutil.setRunName(cfgPath, args.command)
        wlutil.initLogging(args.verbose)

        log = logging.getLogger()

        targetCfg = cfgs[cfgPath]
   
        if args.initramfs:
            targetCfg['initramfs'] = True
            if 'jobs' in targetCfg:
                for j in targetCfg['jobs'].values():
                    j['initramfs'] = True

        if args.command == "build":
            if args.binOnly or args.imgOnly:
                # It's fine if they pass -IB, it just builds both
                wlutil.buildWorkload(cfgPath, cfgs, buildBin=args.binOnly, buildImg=args.imgOnly)
            else:
                wlutil.buildWorkload(cfgPath, cfgs)

        elif args.command == "launch":
            # job-configs are named special internally
            if args.job != 'all':
                if 'jobs' in targetCfg: 
                    args.job = targetCfg['name'] + '-' + args.job
                else:
                    log.error("Job " + args.job + " requested, but no jobs specified in config file\n")
                    parser.print_help()
    
            wlutil.launchWorkload(cfgPath, cfgs, args.job, args.spike)
        elif args.command == "test":
            skipCount = 0
            failCount = 0
            log.info("Running: " + cfgPath)
            res = wlutil.testWorkload(cfgPath, cfgs, args.verbose, spike=args.spike)
            if res is wlutil.testResult.failure:
                print("Test Failed")
                suitePass = False
                failCount += 1
            elif res is wlutil.testResult.skip:
                print("Test Skipped")
                skipCount += 1
            else:
                print("Test Passed")
            log.info("")
        elif args.command == 'clean':
            # with contextlib.suppress(FileNotFoundError):
                if 'bin' in targetCfg:
                    deleteSafe(targetCfg['bin'])
                    deleteSafe(targetCfg['bin'] + '-initramfs')
                if 'img' in targetCfg:
                    deleteSafe(targetCfg['img'])
                if 'jobs' in targetCfg:
                    for jCfg in targetCfg['jobs'].values():
                        if 'bin' in jCfg:
                            deleteSafe(jCfg['bin'])
                            deleteSafe(jCfg['bin'] + '-initramfs')
                        if 'img' in jCfg:
                            deleteSafe(jCfg['img'])
        else:
            log.error("No subcommand specified")
            sys.exit(1)

    if args.command == 'test':
        if suitePass:
            log.info("SUCCESS: All Tests Passed (" + str(skipCount) + " tests skipped)")
            sys.exit(0)
        else:
            log.error("FAILURE: Some tests failed")
            sys.exit(1)

    sys.exit(0)

if __name__ == "__main__":
    main()
