#!/usr/bin/env python3
import sys
import argparse
import json
import subprocess as sp
import os
import shutil
import br.br as br
import fedora.fedora as fed
import pprint
import doit
import glob

jlevel = "-j" + str(os.cpu_count())
root_dir = os.getcwd()
workload_dir = os.path.join(root_dir, "workloads")
image_dir = os.path.join(root_dir, "images")
linux_dir = os.path.join(root_dir, "riscv-linux")
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

    config['cfg-file'] = os.path.abspath(args.config_file)

    config = resolveConfig(config)

    args.func(args, config)

# Use base configs to fill in missing fields and complete the config
def resolveConfig(config):
    # Convert stuff to absolute paths
    for k in ['init', 'run', 'overlay', 'linux-config']:
        if k in config:
            config[k] = os.path.join(workload_dir, config['name'], config[k])
    
    if 'base' not in config:
        if 'distro' not in config:
            raise ValueError("Invalid Configuration: Please provide a base config or distro to base this workload on.")

        # This is one of the bottom-configs (depends only on base distro)
        if config['distro'] == 'br':
            config['builder'] = br.Builder()
        elif config['distro'] == 'fedora':
            config['builder'] = fed.Builder()
        else:
            raise ValueError("Invalid distro: '" + config['distro'] + "'. Available distros are 'fedora' and 'br'")
        config['base-img'] = config['builder'].baseImagePath(config['rootfs-format'])
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

        # Things to set before recursing
        base_cfg['cfg-file'] = config['base-cfg-file']
        base_cfg = resolveConfig(base_cfg)
        # This takes config, but fills in any blank fields with fields from base_cfg
        tmp = base_cfg.copy()
        tmp.update(config)
        config = tmp
        config['base-img'] = base_cfg['img']

    config['bin'] = os.path.join(image_dir, config['name'] + "-bin")
    config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])

    return config

def testMakeBin(bin_path):
    print("Making a binary: " + bin_path)
    sp.check_call("touch " + bin_path, shell=True)

def testMakeImage():
    print("Making an Image")

class doitLoader(doit.cmd_base.TaskLoader):
    workloads = []

    # @staticmethod
    def load_tasks(self, cmd, opt_values, pos_args):
        for w in self.workloads:
            pprint.pprint(w)

        task_list = [doit.task.dict_to_task(w) for w in self.workloads]
        config = {'verbosity': 2}
        return task_list, config

# Generate a task-graph loader for the doit "Run" command
# Note: this doesn't depend on the config or runtime args at all. In theory, it
# could be cached, but I'm not going to bother unless it becomes a performance
# issue.
def buildDepGraph():
    loader = doitLoader()

    # Define the base-distro tasks
    for builder in [br.Builder(), fed.Builder()]:
        for fmt in ['img', 'cpio']:
            img = builder.baseImagePath(fmt)
            loader.workloads.append({
                    'name' : img,
                    'actions' : [(builder.buildBaseImage, [fmt])],
                    'targets' : [img],
                    'uptodate': [(builder.upToDate, [])]
                })

    # Create dependency graph from config files (for all workloads)
    for cfgFile in glob.iglob(os.path.join(workload_dir, "*.json")):
        try:
            with open(cfgFile, 'r') as f:
                config = json.load(f)
            config['cfg-file'] = cfgFile
            config = resolveConfig(config)
        except Exception as e:
            print("Skipping " + cfgFile + ": Unable to parse config:")
            print(repr(e))
            continue

        # Add a rule for the binary
        print("Adding config to loader: " + cfgFile)
        loader.workloads.append({
                'name' : config['bin'],
                'actions' : [(makeBin, [config])],
                'targets' : [config['bin']],
                'file_dep': [config['linux-config']]
                })

        # Add a rule for the image
        file_deps = []
        if 'overlay' in config:
            for root, dirs, files in os.walk(os.path.join(workload_dir, config['overlay'])):
                for f in files:
                    file_deps.append(f)
        if 'init' in config:
            file_deps.append(config['init'])
        if 'run' in config:
            file_deps.append(config['run'])
        
        loader.workloads.append({
            'name' : config['img'],
            'actions' : [(makeImage, [config])],
            'targets' : [config['img']],
            'file_dep' : file_deps,
            'task_dep' : [config['base-img']]
            })

    return loader

def handleBuild(args, config):
    loader = buildDepGraph()
    doit.doit_cmd.DoitMain(loader).run([config['bin'], config['img']])


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

    if 'boot-rootfs' not in config or config['boot-rootfs'] == 'true':
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
    shutil.copy(config['linux-config'], os.path.join(linux_dir, ".config"))
    sp.check_call(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd=linux_dir)
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

        if 'init' in config:
            initScript = os.path.join(workload_dir, config['name'], config['init.sh'])
            if config['rootfs-format'] == 'cpio':
                raise ValueError("CPIO-based images do not support init scripts.")

            config['builder'].applyBootScript(config['img'], initScript)
            launchQemu(config)

        if 'run' in config:
            runScript = os.path.join(workload_dir, config['name'], config['run'])
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
        config['base-img'] = config['builder'].buildBaseImage(config['rootfs-format'])
    elif config['base'] == 'fedora':
        config['builder'] = fed.Builder()
        config['base-img'] = config['builder'].buildBaseImage(config['rootfs-format'])
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


