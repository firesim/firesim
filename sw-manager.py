#!/usr/bin/env python3
import sys
import argparse
import json
import subprocess as sp
import os
import shutil

jlevel = "-j" + str(os.cpu_count())
root_dir = os.getcwd()

def main():
    parser = argparse.ArgumentParser(description="Build and run (in spike or qemu) boot code and disk images for firesim")
    parser.add_argument('-c', '--config',
            help='Configuration file to use (defaults to br-disk.json)',
            nargs='?', default='br-disk.json', dest='config_file')
    subparsers = parser.add_subparsers(title='Commands')

    # Build command
    build_parser = subparsers.add_parser('build', help='Build an image from the given configuration.')
    build_parser.set_defaults(func=handleBuild)

    # Launch command
    launch_parser = subparsers.add_parser('launch', help='Launch an image on a software simulator (defaults to qemu)')
    launch_parser.set_defaults(func=handleLaunch)
    launch_parser.add_argument('-s', '--spike', action='store_true')
    
    args = parser.parse_args()

    with open(args.config_file, 'r') as f:
      config = json.load(f)
    
    config = parseConfig(config)

    args.func(args, config)

def handleBuild(args, config):
    makeImage(config)
    makeBin(config)

def handleLaunch(args, config):
    if args.spike:
      if config['keep-rootfs'] == 'true':
        sys.exit("Spike currently does not support disk-based configurations. Please use an initramfs based image.")

      cmd = ['spike',
          '-p4',
          '-m4096',
          os.path.join("images", config['name'] + '-bin')]

    else:
      cmd = ['qemu-system-riscv64',
        '-nographic',
        '-smp', '4',
        '-machine', 'virt',
        '-m', '4G',
        '-kernel', os.path.join("images", config['name'] + '-bin'),
        '-object', 'rng-random,filename=/dev/urandom,id=rng0',
        '-device', 'virtio-rng-device,rng=rng0',
        '-device', 'virtio-net-device,netdev=usernet',
        '-netdev' ,'user,id=usernet,hostfwd=tcp::10000-:22']

      if config['keep-rootfs'] == 'true':
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
        '-drive', 'file=' + os.path.join("images", config['name'] + '.img') + ',format=raw,id=hd0']
        cmd = cmd + ['-append', 'ro root=/dev/vda']

    sp.check_call(cmd)

# Clean up the provided config and fill in any defaults
def parseConfig(config):
    # Validate the config and fill in defaults
    for field in ['name', 'root-dir', 'linux-config', 'rootfs']:
      if not field in config:
        sys.exit("Invalid configuration (" + args.config_file + "): Missing " + field + " field.")
        
    if not "keep-rootfs" in config:
      config['keep-rootfs'] = 'true'

    return config

def makeBin(config):
    # Now build linux/bbl
    shutil.copy(os.path.join(config['root-dir'], config['linux-config']), "riscv-linux/.config")
    sp.check_call(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd='riscv-linux')
    if not os.path.exists('riscv-pk/build'):
      os.mkdir('riscv-pk/build')

    sp.check_call(['../configure', '--host=riscv64-unknown-elf', '--with-payload=../../riscv-linux/vmlinux'], cwd='riscv-pk/build')
    sp.check_call(['make', jlevel], cwd='riscv-pk/build')
    shutil.copy('riscv-pk/build/bbl',
      os.path.join("images", config['name'] + "-bin"))

def makeImage(config):
    # Build the disk image first because the binary may require it (e.g. initramfs)
    sp.check_call(['make', config['rootfs'], jlevel], cwd=config['root-dir'])
    if config['keep-rootfs'] == 'true':
        shutil.copy(
                os.path.join(config['root-dir'], config['rootfs']),
                os.path.join("images", config['name'] + ".img"))

main()
