import socket
import logging
from .wlutil import *

# The amount of memory to use when launching
launch_mem = "16384"
launch_cores = "4"

# Kinda hacky (technically not guaranteed to give a free port, just very likely)
def get_free_tcp_port():
	tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
	tcp.bind(('', 0))
	addr, port = tcp.getsockname()
	tcp.close()
	return str(port)

# Returns a command string to luanch the given config in spike. Must be called with shell=True.
def getSpikeCmd(config, initramfs=False):
    if 'spike' in config:
        spikeBin = config['spike']
    else:
        spikeBin = 'spike'

    if initramfs:
        return spikeBin + ' -p' + launch_cores + ' -m' + launch_mem + " " + config['bin'] + '-initramfs'
    elif 'img' not in config:
        return spikeBin + ' -p' + launch_cores + ' -m' + launch_mem + " " + config['bin']
    else:
        raise ValueError("Spike does not support disk-based configurations")

# Returns a command string to luanch the given config in qemu. Must be called with shell=True.
def getQemuCmd(config, initramfs=False):
    log = logging.getLogger()

    launch_port = get_free_tcp_port()

    if initramfs:
        exe = config['bin'] + '-initramfs'
    else:
        exe = config['bin']

    cmd = ['qemu-system-riscv64',
           '-nographic',
           '-smp', launch_cores,
           '-machine', 'virt',
           '-m', launch_mem,
           '-kernel', exe,
           '-object', 'rng-random,filename=/dev/urandom,id=rng0',
           '-device', 'virtio-rng-device,rng=rng0',
           '-device', 'virtio-net-device,netdev=usernet',
           '-netdev', 'user,id=usernet,hostfwd=tcp::' + launch_port + '-:22']

    if 'img' in config and not initramfs:
        cmd = cmd + ['-device', 'virtio-blk-device,drive=hd0',
                     '-drive', 'file=' + config['img'] + ',format=raw,id=hd0']

    return " ".join(cmd)

def launchWorkload(cfgName, cfgs, job='all', spike=False):
    log = logging.getLogger()
    baseConfig = cfgs[cfgName]

    # Bare-metal tests don't work on qemu yet
    if baseConfig.get('distro') == 'bare' and spike != True:
        raise RuntimeError("Bare-metal workloads do not currently support Qemu. Please run this workload under spike.")

    if 'jobs' in baseConfig.keys() and job != 'all':
        # Run the specified job
        config = cfgs[cfgName]['jobs'][job]
    else:
        # Run the base image
        config = cfgs[cfgName]
 
    if config['launch']:
        baseResDir = os.path.join(res_dir, getRunName())
        runResDir = os.path.join(baseResDir, config['name'])
        uartLog = os.path.join(runResDir, "uartlog")
        os.makedirs(runResDir)

        if spike:
            if 'img' in config and not config['initramfs']:
                sys.exit("Spike currently does not support disk-based " +
                        "configurations. Please use an initramfs based image.")
            cmd = getSpikeCmd(config, config['initramfs'])
        else:
            cmd = getQemuCmd(config, config['initramfs'])

        sp.check_call(cmd + " | tee " + uartLog, shell=True)

        if 'outputs' in config:
            outputSpec = [ FileSpec(src=f, dst=runResDir + "/") for f in config['outputs']] 
            copyImgFiles(config['img'], outputSpec, direction='out')

        if 'post_run_hook' in config:
            log.info("Running post_run_hook script: " + config['post_run_hook'])
            try:
                run(config['post_run_hook'] + " " + baseResDir, cwd=config['workdir'], shell=True)
            except sp.CalledProcessError as e:
                log.info("\nRun output available in: " + os.path.dirname(runResDir))
                raise RuntimeError("Post run hook failed:\n" + e.output)

        log.info("\nRun output available in: " + os.path.dirname(runResDir))
    else:
        log.info("Workload launch skipped ('launch'=false in config)")


