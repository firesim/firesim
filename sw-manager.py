#!/usr/bin/env python3
import sys
import argparse
import json
import subprocess as sp
import os
import shutil
import br.br as br
import fedora.fedora as fed

jlevel = "-j" + str(os.cpu_count())
root_dir = os.getcwd()
workload_dir = os.path.join(root_dir, "workloads")
image_dir = os.path.join(root_dir, "images")
mnt = os.path.join(root_dir, "disk-mount")

def main():
    parser = argparse.ArgumentParser(
        description="Build and run (in spike or qemu) boot code and disk images for firesim")
    parser.add_argument('-c', '--config',
                        help='Configuration file to use (defaults to br-disk.json)',
                        nargs='?', default='br-disk.json', dest='config_file')
    subparsers = parser.add_subparsers(title='Commands')

    # Build command
    build_parser = subparsers.add_parser(
        'build', help='Build an image from the given configuration.')
    build_parser.set_defaults(func=handleBuild)

    # Launch command
    launch_parser = subparsers.add_parser(
        'launch', help='Launch an image on a software simulator (defaults to qemu)')
    launch_parser.set_defaults(func=handleLaunch)
    launch_parser.add_argument('-s', '--spike', action='store_true')

    args = parser.parse_args()

    with open(args.config_file, 'r') as f:
        config = json.load(f)

    if not "boot-rootfs" in config:
        config['boot-rootfs'] = 'true'

    args.func(args, config)

# Recursively resolve any dependencies and apply config defaults from base configs
# Returns: Updated config file
# Post: config['img'] will point to a valid, built image and binary, and all other config
#       fields will be filled in.
def buildDeps(config):
    config['bin'] = os.path.join(image_dir, config['name'] + "-bin")
    config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])

    if 'linux-config' in config:
        config['linux-config'] = os.path.join(workload_dir, config['name'], config['linux-config'])

    # The two 'bottom' bases (i.e. raw buildroot or fedora) need to be handled specially
    if config['base'] == 'br':
        config['builder'] = br.Builder()
        config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])
        config['base-img'] = config['builder'].buildBaseImage(
            config['rootfs-format'])
    elif config['base'] == 'fedora':
        config['builder'] = fed.Builder()
        config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])
        config['base-img'] = config['builder'].buildBaseImage(
            config['rootfs-format'])
    else:
        # Not one of the bottom bases, look for a config in workloads to base off
        config['base-cfg-file'] = os.path.join(workload_dir, config['base'])
        try:
            with open(config['base-cfg-file'], 'r') as base_cfg_file:
                base_cfg = json.load(base_cfg_file)
        except FileNotFoundError:
            print("Base config '" + config['base-cfg-file'] + "' not found")
            raise
        except:
            print("Base config '" + config['base-cfg-file'] + "' failed to parse")
            raise

        # Setup configs recursively
        base_cfg = buildDeps(base_cfg)

        # Use base_cfg for any values not specified in config
        tmp = base_cfg.copy()
        tmp.update(config)
        config = tmp
        config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])
        config['base-img'] = base_cfg['img']

    # Build this image now that it's dependencies are met
    if config['rootfs-format'] == 'cpio':
        # This is kinda hacky, but initramfs images need the image before the
        # binary (linux links against the image)
        makeImage(config)
        makeBin(config)
    else:
        # But disk-based designs need the binary before the image (to apply any
        # boot scripts). Initramfs designs don't support the boot scripts.
        makeBin(config)
        makeImage(config)

    return config

def handleBuild(args, config):
    buildDeps(config)

def launchSpike(config):
    sp.check_call(['spike', '-p4', '-m4096', config['bin']])

def launchQemu(config):
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

    if config['boot-rootfs'] == 'true':
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
                     '-drive', 'file=' + config['img'] + ',format=raw,id=hd0']
        cmd = cmd + ['-append', 'ro root=/dev/vda']

    sp.check_call(cmd)

def handleLaunch(args, config):
    if args.spike:
        if config['boot-rootfs'] == 'true':
            sys.exit(
                "Spike currently does not support disk-based configurations. Please use an initramfs based image.")
        launchSpike(config)
    else:
        launchQemu(config)

# Now build linux/bbl
def makeBin(config):
    shutil.copy(config['linux-config'], "riscv-linux/.config")
    sp.check_call(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd='riscv-linux')
    if not os.path.exists('riscv-pk/build'):
        os.mkdir('riscv-pk/build')

    sp.check_call(['../configure', '--host=riscv64-unknown-elf',
                   '--with-payload=../../riscv-linux/vmlinux'], cwd='riscv-pk/build')
    sp.check_call(['make', jlevel], cwd='riscv-pk/build')
    shutil.copy('riscv-pk/build/bbl', config['bin'])

def makeImage(config):
    # Check if we need to make the image
    newest = os.stat(os.path.join(workload_dir, config['name'] + ".json")).st_mtime
    for root, dirs, files in os.walk(os.path.join(workload_dir, config['name'])):
        for f in files:
            newest = max(os.stat(os.path.join(root, f)).st_mtime, newest)

    if not os.path.exists(config['img']) or newest > os.stat(config['img']).st_mtime:
        # Need to build
        shutil.copy(config['base-img'], config['img'])

        overlay = os.path.join(workload_dir, config['name'], 'overlay')
        if os.path.exists(overlay):
            applyOverlay(config['img'], overlay, config['rootfs-format'])

        initScript = os.path.join(workload_dir, config['name'], 'init.sh')
        if os.path.exists(initScript):
            if config['rootfs-format'] == 'cpio':
                raise ValueError("CPIO-based images do not support init scripts.")

            config['builder'].applyBootScript(config['img'], initScript)
            launchQemu(config)
            print("Done applying init script")

        runScript = os.path.join(workload_dir, config['name'], 'run.sh')
        if os.path.exists(runScript):
            config['builder'].applyBootScript(config['img'], runScript)
        else:
            # We need to clear the old init script if we don't overwrite it
            # with a run script. Note: it's safe to call this even if we never
            # wrote an init script.
            config['builder'].applyBootScript(config['img'], None)

def toCpio(src, dst):
    sp.check_call(['sudo', 'mount', '-o', 'loop', img, mnt])
    sp.check_call("sudo find -print0 | sudo cpio --null -ov --format=newc > " + dst, shell=True, cwd=mnt)

# Apply the overlay directory "overlay" to the filesystem image "img" which
# has format "fmt" (either 'cpio' or 'img').
# Note that all paths must be absolute
def applyOverlay(img, overlay, fmt):
    if fmt == 'img':
        sp.check_call(['sudo', 'mount', '-o', 'loop', img, mnt])
        try:
            sp.check_call('sudo cp -a ' + overlay +
                          '/*' + " " + mnt, shell=True)
        finally:
            sp.check_call(['sudo', 'umount', mnt])

    elif fmt == 'cpio':
        # Note: a quirk of cpio is that it doesn't really overwrite files when
        # doing an overlay, it actually just appends a new file with the same
        # name. Linux handles this just fine (it uses the latest version of a
        # file), but be aware.
        sp.check_call(
            'sudo find ./* -print0 | sudo cpio -0 -ov -H newc -A -F ' + img, cwd=overlay, shell=True)

    else:
        raise ValueError(
            "Only 'img' and 'cpio' formats are currently supported")


main()
