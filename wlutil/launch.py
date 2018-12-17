import logging
from .wlutil import *

# Returns a command string to luanch the given config in spike. Must be called with shell=True.
def getSpikeCmd(config, initramfs=False):
    if 'spike' in config:
        spikeBin = config['spike']
    else:
        spikeBin = 'spike'

    if initramfs:
        return spikeBin + ' -p4 -m4096 ' + config['bin'] + '-initramfs'
    elif 'img' not in config:
        return spikeBin + ' -p4 -m4096 ' + config['bin']
    else:
        raise ValueError("Spike does not support disk-based configurations")

# Returns a command string to luanch the given config in qemu. Must be called with shell=True.
def getQemuCmd(config, initramfs=False):
    log = logging.getLogger()

    if initramfs:
        exe = config['bin'] + '-initramfs'
    else:
        exe = config['bin']

    cmd = ['qemu-system-riscv64',
           '-nographic',
           '-smp', '4',
           '-machine', 'virt',
           '-m', '4G',
           '-kernel', exe,
           '-object', 'rng-random,filename=/dev/urandom,id=rng0',
           '-device', 'virtio-rng-device,rng=rng0',
           '-device', 'virtio-net-device,netdev=usernet',
           '-netdev', 'user,id=usernet,hostfwd=tcp::10000-:22']

    if 'img' in config and not initramfs:
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
                     '-drive', 'file=' + config['img'] + ',format=raw,id=hd0']
        cmd = cmd + ['-append', '"ro root=/dev/vda"']

    return " ".join(cmd)

def launchWorkload(cfgName, cfgs, job='all', spike=False, initramfs=False):
    log = logging.getLogger()
    baseConfig = cfgs[cfgName]

    # Bare-metal tests don't work on qemu yet
    if baseConfig.get('distro') == 'bare':
        spike = True

    if 'jobs' in baseConfig.keys() and job != 'all':
        # Run the specified job
        config = cfgs[cfgName]['jobs'][job]
    else:
        # Run the base image
        config = cfgs[cfgName]
 
    baseResDir = os.path.join(res_dir, getRunName())
    runResDir = os.path.join(baseResDir, config['name'])
    uartLog = os.path.join(runResDir, "uartlog")
    os.makedirs(runResDir)

    if spike:
        if 'img' in config and 'initramfs' not in config:
            sys.exit("Spike currently does not support disk-based " +
                    "configurations. Please use an initramfs based image.")
        cmd = getSpikeCmd(config, initramfs)
    else:
        cmd = getQemuCmd(config, initramfs)

    sp.check_call(cmd + " | tee " + uartLog, shell=True)

    if 'outputs' in config:
        outputSpec = [ FileSpec(src=f, dst=runResDir + "/") for f in config['outputs']] 
        copyImgFiles(config['img'], outputSpec, direction='out')

    if 'post_run_hook' in config:
        log.info("Running post_run_hook script: " + config['post_run_hook'])
        run(config['post_run_hook'] + " " + baseResDir, cwd=config['workdir'], shell=True)

    log.info("Run output available in: " + os.path.dirname(runResDir))


