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

def addDep(loader, config):

    # Add a rule for the binary
    file_deps = []
    task_deps = []
    if 'linux-config' in config:
        file_deps.append(config['linux-config'])
    
    if 'bin' in config:
        loader.addTask({
                'name' : config['bin'],
                'actions' : [(makeBin, [config])],
                'targets' : [config['bin']],
                'file_dep': file_deps,
                'task_dep' : task_deps,
                'uptodate' : [(checkLinuxUpToDate, [config])]
                })

    # Add a rule for the initramfs version if requested
    # Note that we need both the regular bin and initramfs bin if the base
    # workload needs an init script
    if config['initramfs'] and 'bin' in config:
        file_deps = []
        task_deps = []
        if 'img' in config:
            file_deps = [config['img']]
            task_deps = [config['img']]

        if 'linux-config' in config:
            file_deps.append(config['linux-config'])

        loader.addTask({
                'name' : config['bin'] + '-initramfs',
                'actions' : [(makeBin, [config], {'initramfs' : True})],
                'targets' : [config['bin'] + '-initramfs'],
                'file_dep': file_deps,
                'task_dep' : task_deps,
                'uptodate' : [(checkLinuxUpToDate, [config])]
                })

    # Add a rule for the image (if any)
    file_deps = []
    task_deps = []
    if 'img' in config:
        if 'base-img' in config:
            task_deps = [config['base-img']]
            file_deps = [config['base-img']]
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

def handleHostInit(config):
    log = logging.getLogger()
    if 'host-init' in config:
       log.info("Applying host-init: " + config['host-init'])
       if not os.path.exists(config['host-init']):
           raise ValueError("host-init script " + config['host-init'] + " not found.")

       run([config['host-init']], cwd=config['workdir'])
 
def buildWorkload(cfgName, cfgs, buildBin=True, buildImg=True):
    # This should only be built once (multiple builds will mess up doit)
    global taskLoader
    if taskLoader == None:
        taskLoader = buildDepGraph(cfgs)
        
    config = cfgs[cfgName]

    handleHostInit(config)
    imgList = []
    binList = []

    if buildBin and 'bin' in config:
        binList = [config['bin']]
        if config['initramfs']:
            binList.append(config['bin'] + '-initramfs')
   
    if 'img' in config and buildImg:
        imgList.append(config['img'])

    if 'jobs' in config.keys():
        for jCfg in config['jobs'].values():
            handleHostInit(jCfg)
            if buildBin:
                binList.append(jCfg['bin'])
                if jCfg['initramfs']:
                    binList.append(jCfg['bin'] + '-initramfs')

            if 'img' in jCfg and buildImg:
                imgList.append(jCfg['img'])

    # The order isn't critical here, we should have defined the dependencies correctly in loader 
    ret = doit.doit_cmd.DoitMain(taskLoader).run(binList + imgList)
    if ret != 0:
        raise RuntimeError("Error while building workload")

def makeDrivers(boardDir, linuxSrc):
    driverDirs = pathlib.Path(boardDir).glob("drivers/*")
    makeCmd = "make LINUXSRC=" + str(linuxSrc)

    # Prepare the linux source for building external drivers
    run(["make", "ARCH=riscv", "CROSS_COMPILE=riscv64-unknown-linux-gnu-", "modules_prepare", jlevel], cwd=linuxSrc)

    drivers = []
    for driverDir in driverDirs:
        run(makeCmd, cwd=driverDir, shell=True)
        drivers.extend(list(driverDir.glob("*.ko")))

    return drivers

def setupBoardInitramfs(boardDir, linuxSrc):
    log = logging.getLogger()

    loadScript = initramfs_root / "loadDrivers.sh"

    # clear out any leftover drivers from a previous build
    for oldDriver in initramfs_root.glob("*.ko"):
        oldDriver.unlink()
    if loadScript.exists():
        loadScript.unlink()

    drivers = makeDrivers(boardDir, linuxSrc)

    for driverPath in drivers:
        shutil.copy(driverPath, initramfs_root)

    with open(loadScript, 'w') as f:
        for driverPath in drivers:
            f.write("insmod /" + str(driverPath.name) + "\n")

    if not (initramfs_root / "bin" / "busybox").exists():
        raise RunTimeError("Initramfs root not initialized. Please run './marshal init'")

    # This script adds the device nodes and packages up the initrd.
    run(['fakeroot', '--', './makeInitramfs.sh'], cwd=wlutil_dir)

# Now build linux/bbl
def makeBin(config, initramfs=False):
    log = logging.getLogger()

    # We assume that if you're not building linux, then the image is pre-built (e.g. during host-init)
    if 'linux-config' in config:
        linuxCfg = os.path.join(config['linux-src'], '.config')
        defCfg = os.path.join(config['linux-src'], 'defconfig')
        # Create a defconfig to use as reference
        if not os.path.isfile(defCfg):
            run(['make', 'ARCH=riscv', 'defconfig'], cwd=config['linux-src'])
            shutil.copy(linuxCfg, defCfg)
            with open(defCfg, 'a') as f:
                f.write("CONFIG_BLK_DEV_INITRD=y\n")
                f.write('CONFIG_INITRAMFS_SOURCE="' + str(initramfs_cpio) + '"\n')

        # Create a config from the user fragments
        kconfigEnv = os.environ.copy()
        kconfigEnv['ARCH'] = 'riscv'
        run([os.path.join(config['linux-src'], 'scripts/kconfig/merge_config.sh'),
            defCfg, config['linux-config']], env=kconfigEnv, cwd=config['linux-src']) 

        setupBoardInitramfs(board_dir, config['linux-src'])

        if initramfs:
            with tempfile.NamedTemporaryFile(suffix='.cpio') as tmpCpio:
                toCpio(config, config['img'], tmpCpio.name)
                convertInitramfsConfig(linuxCfg, tmpCpio.name)
                run(['make', 'ARCH=riscv', 'olddefconfig'], cwd=config['linux-src'])
                run(['make', 'ARCH=riscv', 'CROSS_COMPILE=riscv64-unknown-linux-gnu-', 'vmlinux', jlevel], cwd=config['linux-src'])
        else: 
            run(['make', 'ARCH=riscv', 'CROSS_COMPILE=riscv64-unknown-linux-gnu-', 'vmlinux', jlevel], cwd=config['linux-src'])

        # BBL doesn't seem to detect changes in its configuration and won't rebuild if the payload path changes
        if os.path.exists('riscv-pk/build'):
            shutil.rmtree('riscv-pk/build')
        os.mkdir('riscv-pk/build')

        run(['../configure', '--host=riscv64-unknown-elf',
            '--with-payload=' + os.path.join(config['linux-src'], 'vmlinux')], cwd='riscv-pk/build')
        run(['make', jlevel], cwd='riscv-pk/build')

        if initramfs:
            shutil.copy('riscv-pk/build/bbl', config['bin'] + '-initramfs')
        else:
            shutil.copy('riscv-pk/build/bbl', config['bin'])

def makeImage(config):
    log = logging.getLogger()

    # Incremental builds
    if not os.path.exists(config['img']):
        if 'base-img' in config:
            shutil.copy(config['base-img'], config['img'])
  
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

