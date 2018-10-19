#!/usr/bin/env python3
import sys
import argparse
import json
import subprocess as sp
import os
import shutil

jlevel = "-j" + str(os.cpu_count())
root_dir = os.getcwd()

parser = argparse.ArgumentParser(description="Build boot code and disk images for firesim")
parser.add_argument('config_file', help='Configuration file to use (defaults to br-disk.json)', nargs='?', default='br-disk.json')

args = parser.parse_args()

with open(args.config_file, 'r') as f:
  config = json.load(f)

# Validate the config and fill in defaults
for field in ['name', 'root-dir', 'linux-config', 'rootfs']:
  if not field in config:
    sys.exit("Invalid configuration (" + args.config_file + "): Missing " + field + " field.")
    
if not "keep-rootfs" in config:
  config['keep-rootfs'] = 'true'

# Build the disk image first because the binary may require it (e.g. initramfs)
sp.check_call(['make', config['rootfs'], jlevel], cwd=config['root-dir'])
if config['keep-rootfs'] == 'true':
  shutil.copy(os.path.join(config['root-dir'], config['rootfs']), config['name'] + ".img")

# Now build linux/bbl
shutil.copy(os.path.join(config['root-dir'], config['linux-config']), "riscv-linux/.config")
sp.check_call(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd='riscv-linux')
if not os.path.exists('riscv-pk/build'):
  os.mkdir('riscv-pk/build')

sp.check_call(['../configure', '--host=riscv64-unknown-elf', '--with-payload=../../riscv-linux/vmlinux'], cwd='riscv-pk/build')
sp.check_call(['make', jlevel], cwd='riscv-pk/build')
shutil.copy('riscv-pk/build/bbl', config['name'] + "-bin")
