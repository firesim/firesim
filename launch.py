#!/usr/bin/env python3
import sys
import argparse
import json
import subprocess as sp
import os
import shutil

parser = argparse.ArgumentParser(description="Launch the provided configuration in qemu.")
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

qemu_cmd = ['qemu-system-riscv64',
  '-nographic',
  '-smp', '4',
  '-machine', 'virt',
  '-m', '4G',
  '-kernel', config['name'] + '-bin',
  '-object', 'rng-random,filename=/dev/urandom,id=rng0',
  '-device', 'virtio-rng-device,rng=rng0',
  '-device', 'virtio-net-device,netdev=usernet',
  '-netdev' ,'user,id=usernet,hostfwd=tcp::10000-:22']

if config['keep-rootfs'] == 'true':
  qemu_cmd = qemu_cmd + ['-device', 'virtio-blk-device,drive=hd0',
  '-drive', 'file=' + config['name'] + '.img,format=raw,id=hd0']
  qemu_cmd = qemu_cmd + ['-append', 'ro root=/dev/vda']

sp.check_call(qemu_cmd)
