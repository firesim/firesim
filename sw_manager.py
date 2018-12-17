#!/usr/bin/env python3
import sys
import argparse
import os
import logging
import wlutil

# from wlutil.test import cmpOutput
# print(cmpOutput("/data/repos/firesim/sw/firesim-software/runOutput/fed-run-test-2018-12-17--05-02-00-5WB1RF8OYAZUWBY3/", "/data/repos/firesim/sw/firesim-software/test/fed-run/refOutput/"))
# sys.exit()
if 'RISCV' not in os.environ:
    sys.exit("Please source firesim/sourceme-manager-f1.sh first\n")

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
            wlutil.buildWorkload(cfgPath, cfgs)
        elif args.command == "launch":
            # job-configs are named special internally
            if args.job != 'all':
                if 'jobs' in targetCfg: 
                    args.job = targetCfg['name'] + '-' + args.job
                else:
                    log.error("Job " + args.job + " requested, but no jobs specified in config file\n")
                    parser.print_help()
    
            wlutil.launchWorkload(cfgPath, cfgs, args.job, args.spike, args.initramfs)
        elif args.command == "test":
            log.info("Running: " + cfgPath)
            if not wlutil.testWorkload(cfgPath, cfgs, args.verbose):
                suitePass = False
            log.info("")
        else:
            log.error("No subcommand specified")
            sys.exit(1)

    if args.command == 'test':
        if suitePass:
            log.info("SUCCESS: All Tests Passed")
            sys.exit(0)
        else:
            log.error("FAILURE: Some tests failed")
            sys.exit(1)

    sys.exit(0)

if __name__ == "__main__":
    main()
