import doit
import shutil
import tempfile
from .wlutil import *
from .config import *
from .launch import *

taskLoader = None

class doitLoader(doit.cmd_base.TaskLoader):
    workloads = []

    # Idempotent add (no duplicates)
    def addTask(self, tsk):
        if not any(t['name'] == tsk['name'] for t in self.workloads):
            self.workloads.append(tsk)

    def load_tasks(self, cmd, opt_values, pos_args):
        task_list = [doit.task.dict_to_task(w) for w in self.workloads]
        config = {'verbosity': 2}
        return task_list, config

# Checks if the linux kernel used by this config needs to be rebuilt
# Note: this is intended to be used by the doit 'uptodate' feature
def checkLinuxUpToDate(config):
    # XXX There are a number of issues with doing this for real:
    #   - The linux build system always reports that it's not uptodate
    #     (make -q == False), so we'd have to come up with a more clever way
    #   - Using the make -q method is nearly equivalent to makeBin, we need all
    #     that logic (e.g. initramfs, baremetal, etc.).
    #   
    #   The result is that for now we'll always rebuild linux and hope the
    #   makefiles make this not too bad (it adds a few seconds per image). This
    #   function is left here to make it easier if/when we get around to doing
    #   it right.
    return False

def handleHostInit(config):
    log = logging.getLogger()
    if 'host-init' in config:
       log.info("Applying host-init: " + config['host-init'])
       if not os.path.exists(config['host-init']):
           raise ValueError("host-init script " + config['host-init'] + " not found.")

       run([config['host-init']], cwd=config['workdir'])
 
def addDep(loader, config):
    """Adds 'config' to the doit dependency graph ('loader')"""

    hostInit = []
    # Host-init task always runs because we can't tell if its uptodate and we
    # don't know its inputs/outputs.
    if 'host-init' in config:
        loader.addTask({
            'name' : config['host-init'],
            'actions' : [(handleHostInit, [config])],
        })
        hostInit = [config['host-init']]

    # Add a rule for the binary
    file_deps = []
    task_deps = [] + hostInit
    if 'linux-config' in config:
        file_deps.append(config['linux-config'])
    
    # A child binary could conceivably rely on the parent rootfs. This also
    # implicitly depends on the parent's host-init script (whcih the img
    # depends on).
    if 'base-img' in config:
        task_deps.append(config['base-img'])

    if 'bin' in config:
        loader.addTask({
                'name' : config['bin'],
                'actions' : [(makeBin, [config])],
                'targets' : [config['bin']],
                'file_dep': file_deps,
                'task_dep' : task_deps,
                'uptodate' : [(checkLinuxUpToDate, [config])]
                })

    # Add a rule for the nodisk version if requested
    # Note that we need both the regular bin and nodisk bin if the base
    # workload needs an init script
    if config['nodisk'] and 'bin' in config:
        file_deps = []
        task_deps = [] + hostInit
        if 'img' in config:
            file_deps = [config['img']]
            task_deps = [config['img']]

        if 'linux-config' in config:
            file_deps.append(config['linux-config'])

        loader.addTask({
                'name' : config['bin'] + '-nodisk',
                'actions' : [(makeBin, [config], {'nodisk' : True})],
                'targets' : [config['bin'] + '-nodisk'],
                'file_dep': file_deps,
                'task_dep' : task_deps,
                'uptodate' : [(checkLinuxUpToDate, [config])]
                })

    # Add a rule for the image (if any)
    file_deps = []
    task_deps = [] + hostInit
    if 'img' in config:
        if 'base-img' in config:
            task_deps += [config['base-img']]
            file_deps += [config['base-img']]
        if 'files' in config:
            for fSpec in config['files']:
                # Add directories recursively
                if os.path.isdir(fSpec.src):
                    for root, dirs, files in os.walk(fSpec.src):
                        for f in files:
                            fdep = os.path.join(root, f)
                            # Ignore symlinks
                            if not os.path.islink(fdep):
                                file_deps.append(fdep)
                else:
                    # Ignore symlinks
                    if not os.path.islink(fSpec.src):
                        file_deps.append(fSpec.src)			
        if 'guest-init' in config:
            file_deps.append(config['guest-init'].path)
            task_deps.append(config['bin'])
        if 'runSpec' in config and config['runSpec'].path != None:
            file_deps.append(config['runSpec'].path)
        if 'cfg-file' in config:
            file_deps.append(config['cfg-file'])
        
        loader.addTask({
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
                    'file_dep' : dCfg['builder'].fileDeps(),
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

def buildWorkload(cfgName, cfgs, buildBin=True, buildImg=True):
    # This should only be built once (multiple builds will mess up doit)
    global taskLoader
    if taskLoader == None:
        taskLoader = buildDepGraph(cfgs)
        
    config = cfgs[cfgName]

    # handleHostInit(config)
    imgList = []
    binList = []

    if buildBin and 'bin' in config:
        if config['nodisk']:
            binList.append(config['bin'] + '-nodisk')
        else:
            binList.append(config['bin'])
   
    if 'img' in config and buildImg:
        imgList.append(config['img'])

    if 'jobs' in config.keys():
        for jCfg in config['jobs'].values():
            handleHostInit(jCfg)
            if buildBin:
                binList.append(jCfg['bin'])
                if jCfg['nodisk']:
                    binList.append(jCfg['bin'] + '-nodisk')

            if 'img' in jCfg and buildImg:
                imgList.append(jCfg['img'])

    # The order isn't critical here, we should have defined the dependencies correctly in loader 
    ret = doit.doit_cmd.DoitMain(taskLoader).run(binList + imgList)
    if ret != 0:
        raise RuntimeError("Error while building workload")

def makeInitramfs(srcs, cpioDir, includeDevNodes=False):
    """Generate a cpio archive containing each of the sources and store it in cpioDir.
    Return a path to the generated archive.
    srcs: are a list of paths to directories to include, sources will be
    cpioDir: Scratch directory to produce outputs in
    applied in-order (potentially overwriting duplicate files).
    includeDevNodes: If true, will include '/dev/console' and '/dev/tty' special files."""
    
    # Generate individual cpios for each source
    cpios = []
    for src in srcs:
        dst = cpioDir / (src.name + '.cpio')
        toCpio(src, dst)
        cpios.append(dst)

    if includeDevNodes:
        cpios.append(initramfs_dir / 'devNodes.cpio')

    # Generate final cpio
    finalPath = cpioDir / 'initramfs.cpio'
    with open(finalPath, 'wb') as finalF:
        for cpio in cpios:
            with open(cpio, 'rb') as srcF:
                shutil.copyfileobj(srcF, finalF)

    return finalPath

def generateKConfig(kfrags, linuxSrc):
        linuxCfg = os.path.join(linuxSrc, '.config')
        defCfg = gen_dir / 'defconfig'

        # Create a defconfig to use as reference
        run(['make', 'ARCH=riscv', 'defconfig'], cwd=linuxSrc)
        shutil.copy(linuxCfg, defCfg)

        # Create a config from the user fragments
        kconfigEnv = os.environ.copy()
        kconfigEnv['ARCH'] = 'riscv'
        run([os.path.join(linuxSrc, 'scripts/kconfig/merge_config.sh'),
            str(defCfg)] + list(map(str, kfrags)), env=kconfigEnv, cwd=linuxSrc) 

def makeInitramfsKfrag(src, dst):
    with open(dst, 'w') as f:
        f.write("CONFIG_BLK_DEV_INITRD=y\n")
        f.write('CONFIG_INITRAMFS_COMPRESSION=".lzo"\n')
        f.write('CONFIG_INITRAMFS_COMPRESSION_LZO=y\n')
        f.write('CONFIG_INITRAMFS_SOURCE="' + str(src) + '"\n')

# Build all the drivers for this linux source on the specified board.
# Returns a path to a cpio archive containing all the drivers in
# /lib/modules/KERNELVERSION/*.ko
# kfrags: list of paths to kernel configuration fragments to use when building drivers
# boardDir: Path to the board directory. Should have a 'drivers/' subdir
#           containing all the drivers we should build for this board
# linuxSrc: Path to linux source tree to build against
def makeDrivers(kfrags, boardDir, linuxSrc):
    driverDirs = pathlib.Path(boardDir).glob("drivers/*")
    makeCmd = "make LINUXSRC=" + str(linuxSrc)

    # Prepare the linux source for building external drivers
    generateKConfig(kfrags, linuxSrc)
    run(["make", "ARCH=riscv", "CROSS_COMPILE=riscv64-unknown-linux-gnu-", "modules_prepare", jlevel], cwd=linuxSrc)
    kernelVersion = sp.run(["make", "ARCH=riscv", "kernelrelease"], cwd=linuxSrc, stdout=sp.PIPE, universal_newlines=True).stdout.strip()

    drivers = []
    for driverDir in driverDirs:
        # Drivers don't seem to detect changes in the kernel
        run(makeCmd + " clean", cwd=driverDir, shell=True)
        run(makeCmd, cwd=driverDir, shell=True)
        drivers.extend(list(driverDir.glob("*.ko")))

    driverDir = initramfs_dir / "drivers" / "lib" / "modules" / kernelVersion

    # Always start from a clean slate
    try:
        shutil.rmtree(driverDir)
    except FileNotFoundError:
        pass
    driverDir.mkdir(parents=True)

    # Copy in our new drivers
    for driverPath in drivers:
        shutil.copy(driverPath, driverDir)

    # Setup the dependency file needed by modprobe to load the drivers
    run(['depmod', '-b', str(initramfs_dir / "drivers"), kernelVersion])


# Now build linux/bbl
def makeBin(config, nodisk=False):
    log = logging.getLogger()

    # We assume that if you're not building linux, then the image is pre-built (e.g. during host-init)
    if 'linux-config' in config:
        initramfsIncludes = []

        makeDrivers([config['linux-config']], board_dir, config['linux-src'])
        initramfsIncludes.append(initramfs_dir / 'drivers')

        with tempfile.TemporaryDirectory() as cpioDir:
            cpioDir = pathlib.Path(cpioDir)
            initramfsPath = ""
            if nodisk:
                initramfsIncludes += [initramfs_dir / "nodisk"]
                with mountImg(config['img'], mnt):
                    initramfsIncludes += [pathlib.Path(mnt)]
                    # This must be done while in the mountImg context
                    initramfsPath = makeInitramfs(initramfsIncludes, cpioDir, includeDevNodes=True)
            else:
                initramfsIncludes += [initramfs_dir / "disk"]
                initramfsPath = makeInitramfs(initramfsIncludes, cpioDir, includeDevNodes=True)

            makeInitramfsKfrag(initramfsPath, cpioDir / "initramfs.kfrag")
            generateKConfig([config['linux-config'], cpioDir / "initramfs.kfrag"], config['linux-src'])
            run(['make', 'ARCH=riscv', 'CROSS_COMPILE=riscv64-unknown-linux-gnu-', 'vmlinux', jlevel], cwd=config['linux-src'])

        # BBL doesn't seem to detect changes in its configuration and won't rebuild if the payload path changes
        if os.path.exists('riscv-pk/build'):
            shutil.rmtree('riscv-pk/build')
        os.mkdir('riscv-pk/build')

        run(['../configure', '--host=riscv64-unknown-elf',
            '--with-payload=' + os.path.join(config['linux-src'], 'vmlinux')], cwd='riscv-pk/build')
        run(['make', jlevel], cwd='riscv-pk/build')

        if nodisk:
            shutil.copy('riscv-pk/build/bbl', config['bin'] + '-nodisk')
        else:
            shutil.copy('riscv-pk/build/bbl', config['bin'])

def makeImage(config):
    log = logging.getLogger()

    # Incremental builds
    if not os.path.exists(config['img']):
        if 'base-img' in config:
            shutil.copy(config['base-img'], config['img'])
  
    # Convert overlay to file list
    if 'overlay' in config:
        config.setdefault('files', [])
        files = glob.glob(os.path.join(config['overlay'], '*'))
        for f in files:
            config['files'].append(FileSpec(src=f, dst='/'))

    if 'files' in config:
        log.info("Applying file list: " + str(config['files']))
        copyImgFiles(config['img'], config['files'], 'in')

    if 'guest-init' in config:
        log.info("Applying init script: " + config['guest-init'].path)
        if not os.path.exists(config['guest-init'].path):
            raise ValueError("Init script " + config['guest-init'].path + " not found.")

        # Apply and run the init script
        init_overlay = config['builder'].generateBootScriptOverlay(config['guest-init'].path, config['guest-init'].args)
        applyOverlay(config['img'], init_overlay)
        print("Launching: " + config['bin'])
        run(getQemuCmd(config), shell=True, level=logging.INFO)

        # Clear the init script
        run_overlay = config['builder'].generateBootScriptOverlay(None, None)
        applyOverlay(config['img'], run_overlay)

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

        run_overlay = config['builder'].generateBootScriptOverlay(scriptPath, spec.args)
        applyOverlay(config['img'], run_overlay)

