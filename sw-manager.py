#!/usr/bin/env python3
import sys
import argparse
import subprocess as sp
import os
import shutil
import pprint
import doit
import re
import logging
import time
import random
import string
from util.config import *
from util.util import *
import pathlib as pth

def main():
    parser = argparse.ArgumentParser(
        description="Build and run (in spike or qemu) boot code and disk images for firesim")
    parser.add_argument('-c', '--config',
                        help='Configuration file to use (defaults to br-disk.json)',
                        nargs='?', default=os.path.join(root_dir, 'workloads', 'br-disk.json'), dest='config_file')
    parser.add_argument('--workdir', help='Use a custom workload directory', default=os.path.join(root_dir, 'workloads'))
    # parser.add_argument('-c', '--config',
    #                     help='Configuration file to use (defaults to br-disk.json)',
    #                     nargs='?', type=lambda p: pth.Path(p).absolute(),
    #                     default=(root_dir / 'workloads' / 'br-disk.json'), dest='config_file')
    # parser.add_argument('--workdir', help='Use a custom workload directory',
    #         type=lambda p: pth.Path(p).absolute(), default=(root_dir / 'workloads'))
    parser.add_argument('-v', '--verbose',
                        help='Print all output of subcommands to stdout as well as the logs', action='store_true')
    subparsers = parser.add_subparsers(title='Commands', dest='command')

    # Build command
    build_parser = subparsers.add_parser(
        'build', help='Build an image from the given configuration.')
    build_parser.set_defaults(func=handleBuild)
    build_parser.add_argument('-j', '--job', nargs='?', default='all',
            help="Build only the specified JOB (defaults to 'all')")

    # Launch command
    launch_parser = subparsers.add_parser(
        'launch', help='Launch an image on a software simulator (defaults to qemu)')
    launch_parser.set_defaults(func=handleLaunch)
    launch_parser.add_argument('-s', '--spike', action='store_true',
            help="Use the spike isa simulator instead of qemu")
    launch_parser.add_argument('-j', '--job', nargs='?', default='all',
            help="Launch the specified job. Defaults to running the base image.")

    # Init Command
    init_parser = subparsers.add_parser(
            'init', help="Initialize workloads (using 'host_init' script)")
    init_parser.set_defaults(func=handleInit)

    args = parser.parse_args()
    # args.config_file = args.config_file.resolve()
    args.config_file = os.path.abspath(args.config_file)

    initLogging(args)
    log = logging.getLogger()

    # Load all the configs from the workload directory
    cfgs = ConfigManager([os.path.abspath(args.workdir)])
    targetCfg = cfgs[args.config_file]
    
    # Jobs are named with their base config internally 
    if args.command == 'build' or args.command == 'launch':
        if args.job != 'all':
            if 'jobs' in targetCfg: 
                args.job = targetCfg['name'] + '-' + args.job
            else:
                print("Job " + args.job + " requested, but no jobs specified in config file\n")
                parser.print_help()

    args.func(args, cfgs)

class doitLoader(doit.cmd_base.TaskLoader):
    workloads = []

    # @staticmethod
    def load_tasks(self, cmd, opt_values, pos_args):
        task_list = [doit.task.dict_to_task(w) for w in self.workloads]
        config = {'verbosity': 2}
        return task_list, config

def addDep(loader, config):

    # Add a rule for the binary
    file_deps = []
    task_deps = []
    if 'linux-config' in config:
        file_deps.append(config['linux-config'])

    if config.get('rootfs-format') == 'cpio':
        file_deps.append(config['img'])
        task_deps.append(config['img'])

    loader.workloads.append({
            'name' : config['bin'],
            'actions' : [(makeBin, [config])],
            'targets' : [config['bin']],
            'file_dep': file_deps,
            'task_dep' : task_deps
            })

    # Add a rule for the image (if any)
    if 'img' in config:
        if 'base-img' in config:
            task_deps = [config['base-img']]
            file_deps = [config['base-img']]
        # XXX this is broken temporarily (DONT COMMIT)
        # if 'files' in config:
        #     for root, dirs, files in os.walk(config['overlay']):
        #         for f in files:
        #             file_deps.append(os.path.join(root, f))
        if 'init' in config:
            file_deps.append(config['init'])
            task_deps.append(config['bin'])
        if 'runSpec' in config and config['runSpec'].path != None:
            file_deps.append(config['runSpec'].path)
        if 'cfg-file' in config:
            file_deps.append(config['cfg-file'])
        
        loader.workloads.append({
            'name' : config['img'],
            'actions' : [(makeImage, [config])],
            'targets' : [config['img']],
            'file_dep' : file_deps,
            'task_dep' : task_deps
            })

# Generate a task-graph loader for the doit "Run" command
# Note: this doesn't depend on the config or runtime args at all. In theory, it
# could be cached, but I'm not going to bother unless it becomes a performance
# issue.
def buildDepGraph(cfgs):
    loader = doitLoader()

    # Define the base-distro tasks
    for d in distros:
        dCfg = cfgs[d]
        if 'img' in dCfg:
            loader.workloads.append({
                    'name' : dCfg['img'],
                    'actions' : [(dCfg['builder'].buildBaseImage, [])],
                    'targets' : [dCfg['img']],
                    'uptodate': [(dCfg['builder'].upToDate, [])]
                })

    # Non-distro configs 
    for cfgPath in (set(cfgs.keys()) - set(distros)):
        config = cfgs[cfgPath]
        addDep(loader, config)

        if 'jobs' in config.keys():
            for jCfg in config['jobs'].values():
                addDep(loader, jCfg)

    return loader

def handleBuild(args, cfgs):
    loader = buildDepGraph(cfgs)
    config = cfgs[args.config_file]
    binList = [config['bin']]
    imgList = []
    if 'img' in config:
        imgList.append(config['img'])

    if 'jobs' in config.keys():
        if args.job == 'all':
            for jCfg in config['jobs'].values():
                binList.append(jCfg['bin'])
                if 'img' in jCfg:
                    imgList.append(jCfg['img'])
        else:
            jCfg = config['jobs'][args.job]
            binList.append(jCfg['bin'])
            if 'img' in jCfg:
                imgList.append(jCfg['img'])

    if 'img' not in config or config['rootfs-format'] == 'img':
        # Be sure to build the bin first, then the image, because if there is
        # an init script, we need to boot the binary in order to apply it
        doit.doit_cmd.DoitMain(loader).run(binList + imgList)
    elif config['rootfs-format'] == 'cpio':
        # CPIO must build the image first, since the binary links to it.
        # Since CPIO doesn't support init scripts, we don't need the bin first
        doit.doit_cmd.DoitMain(loader).run(imgList + binList)

def launchSpike(config):
    log = logging.getLogger()
    sp.check_call(['spike', '-p4', '-m4096', config['bin']])

def launchQemu(config):
    log = logging.getLogger()

    cmd = ['qemu-system-riscv64',
           '-nographic',
           '-smp', '4',
           '-machine', 'virt',
           '-m', '4G',
           '-kernel', config['bin'],
           '-object', 'rng-random,filename=/dev/urandom,id=rng0',
           '-device', 'virtio-rng-device,rng=rng0',
           '-device', 'virtio-net-device,netdev=usernet',
           '-netdev', 'user,id=usernet,hostfwd=tcp::10000-:22']

    if 'img' in config and config['rootfs-format'] == 'img':
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
                     '-drive', 'file=' + config['img'] + ',format=raw,id=hd0']
        cmd = cmd + ['-append', 'ro root=/dev/vda']

    sp.check_call(cmd)

def handleLaunch(args, cfgs):
    log = logging.getLogger()
    baseConfig = cfgs[args.config_file]
    if 'jobs' in baseConfig.keys() and args.job != 'all':
        # Run the specified job
        config = cfgs[args.config_file]['jobs'][args.job]
    else:
        # Run the base image
        config = cfgs[args.config_file]
    
    if args.spike:
        if 'img' in config and config['rootfs-format'] == 'img':
            sys.exit("Spike currently does not support disk-based " +
                    "configurations. Please use an initramfs based image.")
        launchSpike(config)
    else:
        launchQemu(config)

def handleInit(args, cfgs):
    config = cfgs[args.config_file]
    if 'host_init' in config:
        run([config['host_init']], cwd=config['workdir'])

# Now build linux/bbl
def makeBin(config):
    log = logging.getLogger()

    # We assume that if you're not building linux, then the image is pre-built (e.g. during host-init)
    if 'linux-config' in config:
        # I am not without mercy
        checkInitramfsConfig(config)
                
        shutil.copy(config['linux-config'], os.path.join(linux_dir, ".config"))
        run(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd=linux_dir)
        if not os.path.exists('riscv-pk/build'):
            os.mkdir('riscv-pk/build')

        run(['../configure', '--host=riscv64-unknown-elf',
            '--with-payload=../../riscv-linux/vmlinux'], cwd='riscv-pk/build')
        run(['make', jlevel], cwd='riscv-pk/build')
        shutil.copy('riscv-pk/build/bbl', config['bin'])

def makeImage(config):
    log = logging.getLogger()

    if config['base-format'] == config['rootfs-format']:
        shutil.copy(config['base-img'], config['img'])
    elif config['base-format'] == 'img' and config['rootfs-format'] == 'cpio':
        toCpio(config, config['base-img'], config['img'])
    elif config['base-format'] == 'cpio' and config['rootfs-format'] == 'img':
        raise NotImplementedError("Converting from CPIO to raw img is not currently supported")
    else:
        raise ValueError("Invalid formats for base and/or new image: Base=" +
                config['base-format'] + ", New=" + config['rootfs-format'])

    if 'host_init' in config:
        log.info("Applying host_init: " + config['host_init'])
        if not os.path.exists(config['host_init']):
            raise ValueError("host_init script " + config['host_init'] + " not found.")

        run([config['host_init']], cwd=config['workdir'])

    if 'files' in config:
        log.info("Applying file list: " + str(config['files']))
        applyFiles(config['img'], config['files'], config['rootfs-format'])

    if 'init' in config:
        log.info("Applying init script: " + config['init'])
        if config['rootfs-format'] == 'cpio':
            raise ValueError("CPIO-based images do not support init scripts.")
        if not os.path.exists(config['init']):
            raise ValueError("Init script " + config['init'] + " not found.")

        # Apply and run the init script
        init_overlay = config['builder'].generateBootScriptOverlay(config['init'])
        applyOverlay(config['img'], init_overlay, config['rootfs-format'])
        launchQemu(config)

        # Clear the init script
        run_overlay = config['builder'].generateBootScriptOverlay(None)
        applyOverlay(config['img'], run_overlay, config['rootfs-format'])

    if 'runSpec' in config:
        spec = config['runSpec']
        if spec.command != None:
            log.info("Applying run command: " + spec.command)
            scriptPath = genRunScript(spec.command)
        else:
            log.info("Applying run script: " + spec.path)
            scriptPath = spec.path

        if not os.path.exists(scriptPath):
            raise ValueError("Run script " + scriptPath + " not found.")

        run_overlay = config['builder'].generateBootScriptOverlay(scriptPath)
        applyOverlay(config['img'], run_overlay, config['rootfs-format'])

def toCpio(config, src, dst):
    log = logging.getLogger()

    run(['sudo', 'mount', '-o', 'loop', src, mnt])
    try:
        run("sudo find -print0 | sudo cpio --owner root:root --null -ov --format=newc > " + dst, shell=True, cwd=mnt)
    finally:
        run(['sudo', 'umount', mnt])

# Apply the overlay directory "overlay" to the filesystem image "img" which
# has format "fmt" (either 'cpio' or 'img').
# Note that all paths must be absolute
def applyOverlay(img, overlay, fmt):
    log = logging.getLogger()
    applyFiles(img, [FileSpec(src=os.path.join(overlay, "*"), dst='/')], fmt)
    
# Copies a list of type FileSpec ('files') into the destination image (img)
def applyFiles(img, files, fmt):
    log = logging.getLogger()

    if fmt == 'img':
        run(['sudo', 'mount', '-o', 'loop', img, mnt])
        try:
            for f in files:
                # Overlays may not be owned by root, but the filesystem must be.
                # Rsync lets us chown while copying.
                # Note: shell=True because f.src is allowed to contain globs
                # Note: os.path.join can't handle overlay-style concats (e.g. join('foo/bar', '/baz') == '/baz')
                run('sudo rsync -a --chown=root:root ' + f.src + " " + os.path.normpath(mnt + f.dst), shell=True)
        finally:
            run(['sudo', 'umount', mnt])

    elif fmt == 'cpio':
        run("sudo cpio --owner root:root --null -ov -H newc >> " + img)
        # Note: a quirk of cpio is that it doesn't really overwrite files when
        # doing an overlay, it actually just appends a new file with the same
        # name. Linux handles this just fine (it uses the latest version of a
        # file), but be aware.
        run('sudo find ./* -print0 | sudo cpio --owner root:root --null -ov -H newc >> ' + img,
                cwd=overlay, shell=True)

    else:
        raise ValueError("Only 'img' and 'cpio' formats are currently supported")

main()
