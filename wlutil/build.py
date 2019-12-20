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
        return task_list, {}

def buildBusybox():
    """Builds the local copy of busybox (needed by linux initramfs).
    
    This is called as a doit task (added to the graph in buildDepGraph())
    """

    try:
        checkSubmodule(getOpt('busybox-dir'))
    except SubmoduleError as e:
        return doit.exceptions.TaskFailed(e)
    
    shutil.copy(getOpt('wlutil-dir') / 'busybox-config', getOpt('busybox-dir') / '.config')
    run(['make', getOpt('jlevel')], cwd=getOpt('busybox-dir'))
    shutil.copy(getOpt('busybox-dir') / 'busybox', getOpt('initramfs-dir') / 'disk' / 'bin/')
    return True

def handleHostInit(config):
    log = logging.getLogger()
    if 'host-init' in config:
       log.info("Applying host-init: " + str(config['host-init']))
       if not config['host-init'].path.exists():
           raise ValueError("host-init script " + str(config['host-init']) + " not found.")

       run([config['host-init'].path] + config['host-init'].args, cwd=config['workdir'])
 
def addDep(loader, config):
    """Adds 'config' to the doit dependency graph ('loader')"""

    hostInit = []
    # Host-init task always runs because we can't tell if its uptodate and we
    # don't know its inputs/outputs.
    if 'host-init' in config:
        loader.addTask({
            'name' : str(config['host-init']),
            'actions' : [(handleHostInit, [config])],
        })
        hostInit = [str(config['host-init'])]

    # Add a rule for the binary
    bin_file_deps = []
    bin_task_deps = [] + hostInit + config['base-deps']
    if 'linux-config' in config:
        bin_file_deps.append(config['linux-config'])
        bin_task_deps.append('BuildBusybox')
    
    if 'bin' in config:
        loader.addTask({
                'name' : str(config['bin']),
                'actions' : [(makeBin, [config])],
                'targets' : [str(config['bin'])],
                'file_dep': bin_file_deps,
                'task_dep' : bin_task_deps,
                'uptodate' : [config_changed(checkGitStatus(config.get('linux-src'))),
                    config_changed(checkGitStatus(config.get('pk-src')))]
                })

    # Add a rule for the nodisk version if requested
    if config['nodisk'] and 'bin' in config:
        nodisk_file_deps = bin_file_deps.copy()
        nodisk_task_deps = bin_task_deps.copy()
        if 'img' in config:
            nodisk_file_deps.append(config['img'])
            nodisk_task_deps.append(str(config['img']))

        loader.addTask({
                'name' : str(noDiskPath(config['bin'])),
                'actions' : [(makeBin, [config], {'nodisk' : True})],
                'targets' : [str(noDiskPath(config['bin']))],
                'file_dep': nodisk_file_deps,
                'task_dep' : nodisk_task_deps,
                'uptodate' : [config_changed(checkGitStatus(config.get('linux-src'))),
                    config_changed(checkGitStatus(config.get('pk-src')))]
                })

    # Add a rule for the image (if any)
    img_file_deps = []
    img_task_deps = [] + hostInit + config['base-deps']
    if 'img' in config:
        if 'files' in config:
            for fSpec in config['files']:
                # Add directories recursively
                if fSpec.src.is_dir():
                    for root, dirs, files in os.walk(fSpec.src):
                        for f in files:
                            fdep = os.path.join(root, f)
                            # Ignore symlinks
                            if not os.path.islink(fdep):
                                img_file_deps.append(fdep)
                else:
                    # Ignore symlinks
                    if not os.path.islink(fSpec.src):
                        img_file_deps.append(fSpec.src)			
        if 'guest-init' in config:
            img_file_deps.append(config['guest-init'].path)
            img_task_deps.append(str(config['bin']))
        if 'runSpec' in config and config['runSpec'].path != None:
            img_file_deps.append(config['runSpec'].path)
        if 'cfg-file' in config:
            img_file_deps.append(config['cfg-file'])
        
        loader.addTask({
            'name' : str(config['img']),
            'actions' : [(makeImage, [config])],
            'targets' : [config['img']],
            'file_dep' : img_file_deps,
            'task_dep' : img_task_deps
            })

# Generate a task-graph loader for the doit "Run" command
# Note: this doesn't depend on the config or runtime args at all. In theory, it
# could be cached, but I'm not going to bother unless it becomes a performance
# issue.
def buildDepGraph(cfgs):
    loader = doitLoader()

    # Linux-based workloads depend on this task
    loader.workloads.append({
        'name' : 'BuildBusybox',
        'actions' : [(buildBusybox, [])],
        'targets' : [getOpt('initramfs-dir') /'disk' / 'bin' / 'busybox'],
        'uptodate': [config_changed(checkGitStatus(getOpt('busybox-dir'))),
            config_changed(getToolVersions())]
        })

    # Define the base-distro tasks
    for d in distros:
        dCfg = cfgs[d]
        if 'img' in dCfg:
            loader.workloads.append({
                    'name' : str(dCfg['img']),
                    'actions' : [(dCfg['builder'].buildBaseImage, [])],
                    'targets' : [dCfg['img']],
                    'file_dep' : dCfg['builder'].fileDeps(),
                    'uptodate': dCfg['builder'].upToDate() +
                        [config_changed(getToolVersions())]
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
            binList.append(noDiskPath(config['bin']))
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
                    binList.append(noDiskPath(jCfg['bin']))

            if 'img' in jCfg and buildImg:
                imgList.append(jCfg['img'])

    # The order isn't critical here, we should have defined the dependencies correctly in loader 
    doitHandle = doit.doit_cmd.DoitMain(taskLoader, extra_config={'run': getOpt('doitOpts')})
    return doitHandle.run([str(p) for p in binList + imgList])

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
        cpios.append(getOpt('initramfs-dir') / 'devNodes.cpio')

    # Generate final cpio
    finalPath = cpioDir / 'initramfs.cpio'
    with open(finalPath, 'wb') as finalF:
        for cpio in cpios:
            with open(cpio, 'rb') as srcF:
                shutil.copyfileobj(srcF, finalF)

    return finalPath

def generateKConfig(kfrags, linuxSrc):
        linuxCfg = linuxSrc / '.config'
        defCfg = getOpt('gen-dir') / 'defconfig'

        # Create a defconfig to use as reference
        run(['make', 'ARCH=riscv', 'defconfig'], cwd=linuxSrc)
        shutil.copy(linuxCfg, defCfg)

        # Create a config from the user fragments
        kconfigEnv = os.environ.copy()
        kconfigEnv['ARCH'] = 'riscv'
        run([linuxSrc / 'scripts/kconfig/merge_config.sh',
            str(defCfg)] + list(map(str, kfrags)), env=kconfigEnv, cwd=linuxSrc) 

def makeInitramfsKfrag(src, dst):
    with open(dst, 'w') as f:
        f.write("CONFIG_BLK_DEV_INITRD=y\n")
        f.write('CONFIG_INITRAMFS_COMPRESSION=".lzo"\n')
        f.write('CONFIG_INITRAMFS_COMPRESSION_LZO=y\n')
        f.write('CONFIG_INITRAMFS_SOURCE="' + str(src) + '"\n')

def makeDrivers(kfrags, boardDir, linuxSrc):
    """Build all the drivers for this linux source on the specified board.
    Returns a path to a cpio archive containing all the drivers in
    /lib/modules/KERNELVERSION/*.ko

    kfrags: list of paths to kernel configuration fragments to use when building drivers
    boardDir: Path to the board directory. Should have a 'drivers/' subdir
        containing all the drivers we should build for this board
    linuxSrc: Path to linux source tree to build against
    """

    makeCmd = "make LINUXSRC=" + str(linuxSrc)

    # Prepare the linux source for building external drivers
    generateKConfig(kfrags, linuxSrc)
    run(["make", "ARCH=riscv", "CROSS_COMPILE=riscv64-unknown-linux-gnu-", "modules_prepare", getOpt('jlevel')], cwd=linuxSrc)
    kernelVersion = sp.run(["make", "ARCH=riscv", "kernelrelease"], cwd=linuxSrc, stdout=sp.PIPE, universal_newlines=True).stdout.strip()

    drivers = []
    for driverDir in getOpt('driver-dirs'):
        checkSubmodule(driverDir)

        # Drivers don't seem to detect changes in the kernel
        run(makeCmd + " clean", cwd=driverDir, shell=True)
        run(makeCmd, cwd=driverDir, shell=True)
        drivers.extend(list(driverDir.glob("*.ko")))

    driverDir = getOpt('initramfs-dir') / "drivers" / "lib" / "modules" / kernelVersion

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
    run(['depmod', '-b', str(getOpt('initramfs-dir') / "drivers"), kernelVersion])


def makeBin(config, nodisk=False):
    """Build the binary specified in 'config'.

    This is called as a doit task (see buildDepGraph() and addDep())
    """

    log = logging.getLogger()

    # We assume that if you're not building linux, then the image is pre-built (e.g. during host-init)
    if 'linux-config' in config:
        initramfsIncludes = []

        # Some submodules are only needed if building Linux
        try:
            checkSubmodule(config['linux-src'])
            checkSubmodule(config['pk-src'])
            
            makeDrivers([config['linux-config']], getOpt('board-dir'), config['linux-src'])
        except SubmoduleError as err:
            return doit.exceptions.TaskFailed(err)

        initramfsIncludes.append(getOpt('initramfs-dir') / 'drivers')

        with tempfile.TemporaryDirectory() as cpioDir:
            cpioDir = pathlib.Path(cpioDir)
            initramfsPath = ""
            if nodisk:
                initramfsIncludes += [getOpt('initramfs-dir') / "nodisk"]
                with mountImg(config['img'], getOpt('mnt-dir')):
                    initramfsIncludes += [getOpt('mnt-dir')]
                    # This must be done while in the mountImg context
                    initramfsPath = makeInitramfs(initramfsIncludes, cpioDir, includeDevNodes=True)
            else:
                initramfsIncludes += [getOpt('initramfs-dir') / "disk"]
                initramfsPath = makeInitramfs(initramfsIncludes, cpioDir, includeDevNodes=True)

            makeInitramfsKfrag(initramfsPath, cpioDir / "initramfs.kfrag")
            generateKConfig([config['linux-config'], cpioDir / "initramfs.kfrag"], config['linux-src'])
            run(['make', 'ARCH=riscv', 'CROSS_COMPILE=riscv64-unknown-linux-gnu-', 'vmlinux', getOpt('jlevel')], cwd=config['linux-src'])

        # BBL doesn't seem to detect changes in its configuration and won't rebuild if the payload path changes
        pk_build = (config['pk-src'] / 'build')
        if pk_build.exists():
            shutil.rmtree(pk_build)
        pk_build.mkdir()

        run(['../configure', '--host=riscv64-unknown-elf',
            '--with-payload=' + str(config['linux-src'] / 'vmlinux')], cwd=pk_build)
        run(['make', getOpt('jlevel')], cwd=pk_build)

        if nodisk:
            shutil.copy(pk_build / 'bbl', noDiskPath(config['bin']))
        else:
            shutil.copy(pk_build / 'bbl', config['bin'])

    return True

def makeImage(config):
    log = logging.getLogger()

    # Incremental builds
    if not config['img'].exists():
        if 'base-img' in config:
            shutil.copy(config['base-img'], config['img'])
  
    # Resize if needed
    if config['img-sz'] != 0:
        resizeFS(config['img'], config['img-sz'])

    # Convert overlay to file list
    if 'overlay' in config:
        config.setdefault('files', [])
        files = config['overlay'].glob('*')
        for f in files:
            config['files'].append(FileSpec(src=f, dst=pathlib.Path('/')))

    if 'files' in config:
        log.info("Applying file list: " + str(config['files']))
        copyImgFiles(config['img'], config['files'], 'in')

    if 'guest-init' in config:
        log.info("Applying init script: " + str(config['guest-init'].path))
        if not config['guest-init'].path.exists():
            raise ValueError("Init script " + str(config['guest-init'].path) + " not found.")

        # Apply and run the init script
        init_overlay = config['builder'].generateBootScriptOverlay(str(config['guest-init'].path), config['guest-init'].args)
        applyOverlay(config['img'], init_overlay)
        print("Launching: " + str(config['bin']))
        run(getQemuCmd(config), shell=True, level=logging.DEBUG)

        # Clear the init script
        run_overlay = config['builder'].generateBootScriptOverlay(None, None)
        applyOverlay(config['img'], run_overlay)

    if 'runSpec' in config:
        spec = config['runSpec']
        if spec.command != None:
            log.info("Applying run command: " + str(spec.command))
            scriptPath = genRunScript(spec.command)
        else:
            log.info("Applying run script: " + str(spec.path))
            scriptPath = spec.path

        if not scriptPath.exists():
            raise ValueError("Run script " + str(scriptPath) + " not found.")

        run_overlay = config['builder'].generateBootScriptOverlay(scriptPath, spec.args)
        applyOverlay(config['img'], run_overlay)
    
    # Tighten the image size if requested
    if config['img-sz'] == 0:
        resizeFS(config['img'], 0)
