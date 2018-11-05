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
import re

jlevel = "-j" + str(os.cpu_count())
root_dir = os.getcwd()
workload_dir = os.path.join(root_dir, "workloads")
image_dir = os.path.join(root_dir, "images")
linux_dir = os.path.join(root_dir, "riscv-linux")
mnt = os.path.join(root_dir, "disk-mount")

# Some warnings might be missed if they happen early on. This string will
# be printed at the very end
delayed_warning = ""

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

    try:
        with open(args.config_file, 'r') as f:
            config = json.load(f)
    except:
        Print("Unable to open or parse config file: " + args.config_file)
        raise

    config['cfg-file'] = os.path.abspath(args.config_file)

    config = resolveConfig(config)

    args.func(args, config)
    print(delayed_warning)

# Use base configs to fill in missing fields and complete the config
# Note that after calling resolveConfig, all paths are absolute
def resolveConfig(config):
    # This is a comprehensive list of all user-defined config options
    # See the documentation for their meaning
    configUser = ['name', 'distro', 'base', 'rootfs-format', 'linux-config',
            'host-init', 'overlay', 'run', 'init']

    # This is a comprehensive list of all options set during config parsing
    # (but not explicitly provided by the user)
    configDerived = [
            'builder', # A handle to the base-distro object (e.g. br.Builder)
            'base-img', # The filesystem image to use when building this workload
            'base-format', # The format of base-img
            'base-cfg-file', # Path to config file used by base configuration
            'cfg-file', # Path to this workloads raw config file
            ]

    # These are the user-defined options that should be converted to absolute
    # paths (from workload-relative). Derived options are already absolute.
    configToAbs = ['init', 'run', 'overlay', 'linux-config']

    # These are the options that should be inherited from base configs (if not
    # explicitly provided)
    configInherit = ['run', 'overlay', 'linux-config', 'builder']

    # First fill in any missing options with None for consistency
    for k in configUser + configDerived:
        if k not in config:
            config[k] = None

    # Convert stuff to absolute paths
    for k in configToAbs:
        if config[k] != None:
            config[k] = os.path.join(workload_dir, config['name'], config[k])

    if config['base'] == None:
        # This is one of the bottom-configs (depends only on base distro)
        if config['distro'] == 'br':
            config['builder'] = br.Builder()
        elif config['distro'] == 'fedora':
            config['builder'] = fed.Builder()
        else:
            raise ValueError("Invalid distro: '" + config['distro'] +
                "'. Please specify one of the available distros " + 
                "('fedora' or 'br') or speficy a workload to base off.")

        config['base-img'] = config['builder'].baseImagePath(config['rootfs-format'])
        config['base-format'] = config['rootfs-format']
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

        # Inherit missing values from base config
        for k in configInherit:
            if config[k] == None:
                config[k] = base_cfg[k]

        config['base-img'] = base_cfg['img']
        config['base-format'] = base_cfg['rootfs-format']

    config['bin'] = os.path.join(image_dir, config['name'] + "-bin")
    config['img'] = os.path.join(image_dir, config['name'] + "." + config['rootfs-format'])

    return config

class doitLoader(doit.cmd_base.TaskLoader):
    workloads = []

    # @staticmethod
    def load_tasks(self, cmd, opt_values, pos_args):
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
            print("\t" + repr(e))
            continue

        # Add a rule for the binary
        file_deps = [config['linux-config']]

        task_deps = []
        if config['rootfs-format'] == 'cpio':
            file_deps.append(config['img'])
            task_deps.append(config['img'])

        loader.workloads.append({
                'name' : config['bin'],
                'actions' : [(makeBin, [config])],
                'targets' : [config['bin']],
                'file_dep': file_deps,
                'task_dep' : task_deps
                })

        # Add a rule for the image
        task_deps = [config['base-img']]
        file_deps = [config['base-img']]
        if config['overlay'] != None:
            for root, dirs, files in os.walk(config['overlay']):
                for f in files:
                    file_deps.append(os.path.join(root, f))
        if config['init'] != None:
            file_deps.append(config['init'])
            task_deps.append(config['bin'])
        if config['run'] != None:
            file_deps.append(config['run'])
        
        loader.workloads.append({
            'name' : config['img'],
            'actions' : [(makeImage, [config])],
            'targets' : [config['img']],
            'file_dep' : file_deps,
            'task_dep' : task_deps
            })

    return loader

def handleBuild(args, config):
    loader = buildDepGraph()
    if config['rootfs-format'] == 'img':
        # Be sure to build the bin first, then the image, because if there is
        # an init script, we need to boot the binary in order to apply it
        doit.doit_cmd.DoitMain(loader).run([config['bin'], config['img']])
        # doit.doit_cmd.DoitMain(loader).run(['info', "/data/repos/firesim/sw/firesim-software/images/br-disk.img"])
    elif config['rootfs-format'] == 'cpio':
        # CPIO must build the image first, since the binary links to it.
        # Since CPIO doesn't support init scripts, we don't need the bin first
        doit.doit_cmd.DoitMain(loader).run([config['img'], config['bin']])

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

    if config['rootfs-format'] == 'img':
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
                     '-drive', 'file=' + config['img'] + ',format=raw,id=hd0']
        cmd = cmd + ['-append', 'ro root=/dev/vda']

    sp.check_call(cmd)

def handleLaunch(args, config):
    if args.spike:
        if config['rootfs-format'] == 'img':
            sys.exit("Spike currently does not support disk-based " +
                    "configurations. Please use an initramfs based image.")
        launchSpike(config)
    else:
        launchQemu(config)

# Now build linux/bbl
def makeBin(config):
    global delayed_warning
    # I am not without mercy
    if config['rootfs-format'] == 'cpio':
       with open(config['linux-config'], 'rt') as f:
           linux_config = f.read()
           match = re.search(r'^CONFIG_INITRAMFS_SOURCE=(.*)$', linux_config, re.MULTILINE)
           if match:
               initramfs_src = os.path.normpath(os.path.join(linux_dir, match.group(1).strip('\"')))
               if initramfs_src != config['img']:
                   delayed_warning += "WARNING: The workload linux config " + \
                   "'CONFIG_INITRAMFS_SOURCE' option doesn't point to this " + \
                   "workload's image:\n" + \
                   "\tCONFIG_INITRAMFS_SOURCE = " + initramfs_src + "\n" +\
                   "\tWorkload Image = " + config['img'] + "\n" + \
                   "You likely want to change this option to:\n" +\
                   "\tCONFIG_INITRAMFS_SOURCE=" + os.path.relpath(config['img'], linux_dir)
           else:
               delayed_warning += "WARNING: The workload linux config doesn't include a " + \
               "CONFIG_INITRAMFS_SOURCE option, but this workload is " + \
               "using cpio for it's image.\n" + \
               "You likely want to change this option to:\n" + \
               "\tCONFIG_INITRAMFS_SOURCE=" + os.path.relpath(config['img'], linux_dir)
             
    shutil.copy(config['linux-config'], os.path.join(linux_dir, ".config"))
    sp.check_call(['make', 'ARCH=riscv', 'vmlinux', jlevel], cwd=linux_dir)
    if not os.path.exists('riscv-pk/build'):
        os.mkdir('riscv-pk/build')

    sp.check_call(['../configure', '--host=riscv64-unknown-elf',
                   '--with-payload=../../riscv-linux/vmlinux'], cwd='riscv-pk/build')
    sp.check_call(['make', jlevel], cwd='riscv-pk/build')
    shutil.copy('riscv-pk/build/bbl', config['bin'])

def makeImage(config):
    if config['base-format'] == config['rootfs-format']:
        shutil.copy(config['base-img'], config['img'])
    elif config['base-format'] == 'img' and config['rootfs-format'] == 'cpio':
        toCpio(config, config['base-img'], config['img'])
    elif config['base-format'] == 'cpio' and config['rootfs-format'] == 'img':
        raise NotImplementedError("Converting from CPIO to raw img is not currently supported")
    else:
        raise ValueError("Invalid formats for base and/or new image: Base=" +
                config['base-format'] + ", New=" + config['rootfs-format'])

    if config['host-init'] != None:
        sp.check_call([config['host-init']], cwd=os.path.join(workload_dir, config['name']))

    if config['overlay'] != None:
        applyOverlay(config['img'], config['overlay'], config['rootfs-format'])

    if config['init'] != None:
        if config['rootfs-format'] == 'cpio':
            raise ValueError("CPIO-based images do not support init scripts.")

        # Apply and run the init script
        init_overlay = config['builder'].generateBootScriptOverlay(config['init'])
        applyOverlay(config['img'], init_overlay, config['rootfs-format'])
        launchQemu(config)

        # Clear the init script
        run_overlay = config['builder'].generateBootScriptOverlay(None)
        applyOverlay(config['img'], run_overlay, config['rootfs-format'])

    if config['run'] != None:
        run_overlay = config['builder'].generateBootScriptOverlay(config['run'])
        applyOverlay(config['img'], run_overlay, config['rootfs-format'])

def toCpio(config, src, dst):
    sp.check_call(['sudo', 'mount', '-o', 'loop', src, mnt])
    try:
        if config['distro'] == 'fedora':
            # This is a hack to get fedora to boot, I'm not wild about
            # modifying the source image but cpio can't append to large
            # archives so this is the only option (otherwise we'd just add this
            # to the overlay)
            sp.check_call("sudo ln -s -f /sbin/init " + os.path.join(mnt, "init"), shell=True)

        sp.check_call("sudo find -print0 | sudo cpio --null -ov --format=newc > " + dst, shell=True, cwd=mnt)
    finally:
        if config['distro'] == 'fedora':
            sp.check_call("sudo rm " + os.path.join(mnt, "init"), shell=True)

        sp.check_call(['sudo', 'umount', mnt])

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
